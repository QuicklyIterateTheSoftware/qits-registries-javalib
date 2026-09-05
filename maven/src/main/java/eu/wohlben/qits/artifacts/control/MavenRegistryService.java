package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.MavenProxyMetadata;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.error.MavenException;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The maven repository's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, for the same load-bearing reason {@code
 * NpmRegistryService}'s are: the callers are raw Vert.x route handlers, which run with <b>no CDI
 * request context and no transaction</b>. Drop an annotation and the maven routes fail with {@code
 * ContextNotActiveException} at runtime only, with a green {@code mvn verify} behind them.
 *
 * <p>Artifact bytes stay deliberately outside all of it — they are staged into {@code BlobStore} by
 * the route before anything here is called, so a slow deploy cannot time a transaction out. And
 * nothing here assembles a document: {@code maven-metadata.xml} is derived state, assembled per
 * request from these rows in the wire package, so it can never become a second source of truth.
 */
@ApplicationScoped
public class MavenRegistryService {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(MavenRegistryService.class);

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;
  @Inject MavenProxyMetadataRepository metadata;
  @Inject MavenAccessTracker accessTracker;

  /** A stored file, flattened for the serve path. */
  public record StoredArtifact(String path, String blobId, long sizeBytes) {}

  /** One row under a metadata prefix: the path and when it landed. */
  public record StoredPath(String path, Instant createdAt) {}

  /** A cached upstream {@code maven-metadata.xml}: the document verbatim, its validators, its age. */
  public record CachedMetadata(String doc, String etag, String lastModified, Instant fetchedAt) {}

  /**
   * Resolves the first path segment after {@code /artifacts/maven/} to a maven-typed repository
   * row.
   *
   * <p>Repositories are not created implicitly, exactly as on {@code /v2} and {@code
   * /artifacts/npm}: an unknown or wrong-typed name is a 404 whose message names the ensure
   * endpoint and the type to ask for. The two seeded rows — {@code maven} (hosted) and {@code
   * central} (a pull-through cache of Maven Central) — mean a fresh deployment needs no manual step
   * for the platform's own convention; every other name still has to be asked for, so a typo fails
   * loudly rather than quietly minting a namespace.
   *
   * <p>Wrapped in {@link DbRetry#call} because it is the first database touch of every resolve and
   * every deploy: without it a postgres cutover answers "no such maven repository" for a repository
   * that exists, and a build acts on that 404 — including this platform's own builds, which resolve
   * through here. A plain read, and the caller is a raw route handler that opens no transaction.
   *
   * @return which of the two maven types this repository is, since the serve and deploy paths both
   *     branch on it
   */
  @ActivateRequestContext
  public String requireMavenRepository(String name) {
    return DbRetry.call(
        "maven repository lookup for " + name,
        () -> {
          ArtifactRepository repository = name == null ? null : repositories.findById(name);
          if (repository == null
              || (!MavenPackagesProfile.KEY.equals(repository.type)
                  && !MavenProxyProfile.KEY.equals(repository.type))) {
            throw new MavenException(
                404,
                "no such maven repository '"
                    + name
                    + "'; create it with PUT /artifacts/api/repositories/"
                    + name
                    + " {\"type\":\"maven-packages\"} (or \"maven-proxy\")");
          }
          return repository.type;
        });
  }

  @ActivateRequestContext
  public Optional<StoredArtifact> findArtifact(String repository, String path) {
    return artifacts
        .findOne(repository, path)
        .map(row -> new StoredArtifact(row.path, row.blobId, row.sizeBytes));
  }

  /**
   * Records that a GET served this deployed file — the maven half of the access basis both settled
   * GC strategies read (artifacts-gc-plan.md, "Settlement").
   *
   * <p>Called from the stored-file serve only. The derived {@code maven-metadata.xml} and the
   * derived checksums are computed per request and are not this row's bytes, and a resolver never
   * fetches a checksum without the file it belongs to — so touching on those would record an access
   * the client did not make.
   *
   * <p>Coalesced to one write per row per hour inside {@link MavenAccessTracker}.
   */
  @ActivateRequestContext
  public void touchArtifact(String repository, String path) {
    accessTracker.touchMavenArtifact(
        repository, path, Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  /**
   * Writes one deployed file.
   *
   * <p>The immutability check lives here rather than in the route because it has to be inside the
   * same transaction as the insert — checking outside it would make two concurrent deploys of the
   * same path a race that both sides win. The path space has three classes (maven-repository-plan.md
   * §3.6) and each gets the honest rule:
   *
   * <ul>
   *   <li><b>Release paths</b> are immutable: a re-deploy of identical bytes is an idempotent
   *       no-op (deploy retries are normal, and content addressing makes the retry free); a
   *       re-deploy of different bytes is {@code 403}, naming the version and the rule — a
   *       coordinate that resolved to two different jars over its lifetime is the mutability this
   *       registry exists to refuse.
   *   <li><b>Timestamped snapshot files</b> are unique by construction — one deploy, one filename —
   *       so they take the release rule: identical is a no-op, different bytes at the same
   *       timestamped name is a {@code 403} that means the client's clock or build counter
   *       collided, which is worth saying loudly rather than absorbing.
   *   <li><b>Literal {@code -SNAPSHOT} filenames</b> are mutable: the coordinate is a moving target
   *       by definition, and a {@code 403} here would break a legitimate redeploy while buying
   *       nothing — the timestamped form is what every modern client sends, so this class exists
   *       for compatibility, not as the platform's own convention.
   * </ul>
   *
   * @throws MavenException {@code 403} on a re-deploy of an immutable path with different bytes
   */
  @ActivateRequestContext
  @Transactional
  public void deploy(
      String repository, MavenLayout.ArtifactPath parsed, String blobId, long sizeBytes) {
    Optional<MavenArtifact> existing = artifacts.findOne(repository, parsed.path());
    if (existing.isEmpty()) {
      MavenArtifact row = new MavenArtifact();
      row.repository = repository;
      row.path = parsed.path();
      row.blobId = blobId;
      row.sizeBytes = sizeBytes;
      row.createdAt = Instant.now();
      artifacts.persist(row);
      return;
    }
    MavenArtifact row = existing.get();
    if (row.blobId.equals(blobId)) {
      return;
    }
    if (MavenLayout.parseTimestampedSnapshot(parsed.artifactId(), parsed.version(), parsed.file())
        != null) {
      throw new MavenException(
          403,
          "cannot deploy over the existing "
              + parsed.path()
              + " — a timestamped snapshot name is unique by construction; different bytes at the"
              + " same name means the client's clock or build counter collided");
    }
    if (!MavenLayout.isMutablePath(parsed)) {
      throw new MavenException(
          403,
          "cannot deploy over the existing "
              + parsed.path()
              + " — version "
              + parsed.version()
              + " is immutable here; bump the version");
    }
    // The one mutable class: a literal -SNAPSHOT filename is a moving target by definition.
    row.blobId = blobId;
    row.sizeBytes = sizeBytes;
    row.createdAt = Instant.now();
  }

  /**
   * Deletes one deployed file — the whole of a maven collection, and the only way a {@code
   * maven_artifact} row ever leaves this service.
   *
   * <p><b>Package-private, reached only through {@link MavenRegistryCollection}</b> and called only
   * by the {@code gc} module's {@code MavenPackagesGcAdapter} — the same shape and the same reason
   * as {@code NpmRegistryService.collect}, {@code OciRegistryService.collectTag} and {@code
   * BlobStore.delete}. There is no client-facing {@code DELETE} on {@code /artifacts/maven} and this
   * does not add one.
   *
   * <p><b>One file at a time, and the caller collects a whole version's set.</b> That split is
   * deliberate: which files belong to one coordinate is a question about the layout, which the
   * collector already answers, while what a row deletion <em>is</em> stays here. Both derived
   * documents follow for free — {@code maven-metadata.xml} and every checksum are computed from the
   * surviving rows per request, so removing a row removes the coordinate from the document with
   * nothing left to rewrite, and there is no second source of truth to keep in step.
   *
   * <p>The file's blob is not touched. Blobs dedupe across every repository type, so what may be
   * unlinked is never one type's question; the sweep answers it.
   *
   * @throws IllegalStateException no such row — the store moved since the plan was computed
   */
  @ActivateRequestContext
  @Transactional
  void collect(String repository, String path) {
    MavenArtifact row =
        artifacts
            .findOne(repository, path)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no such maven path "
                            + path
                            + " to collect — the store moved since the plan was computed"));
    artifacts.delete(row);
  }

  // --- the pull-through cache -------------------------------------------------------------------

  /**
   * Records a file the proxy just pulled through, if it is not already known.
   *
   * <p>An ordinary {@code maven_artifact} row: the serve path stays <b>one</b> code path for both
   * maven types — look the path up, and if it is missing and this is a proxy, go and get it — which
   * is the npm proxy's shape verbatim.
   *
   * <p>Idempotent, because two concurrent builds resolving the same dependency are the normal case
   * rather than the edge. The hosted immutability rules are deliberately not applied: nothing here
   * is ours to guarantee, and a path re-fetched after eviction must be able to land again.
   */
  @ActivateRequestContext
  public void recordProxiedArtifact(
      String repository, String path, String blobId, long sizeBytes) {
    // THE PRIMARY KEY DECIDES, not a read this method did a moment earlier. The old shape asked
    // findOne and then persisted if the answer was "absent", which leaves a window between the two
    // halves — and the paragraph above, about two concurrent builds resolving one dependency being
    // the normal case rather than the edge, is precisely the traffic that lands in it. See
    // MavenArtifactRepository.store for what that race cost on 2026-09-05.
    //
    // A TRANSACTION OF ITS OWN, which is the load-bearing half. A failed insert dooms the
    // transaction it ran in, so absorbing the duplicate inside a caller's transaction would leave
    // that caller committed to a rollback it never asked for. Here the doomed transaction is this
    // one, it is the last thing it was going to do, and its failure means the row is there — which
    // is the outcome the caller wanted.
    try {
      QuarkusTransaction.requiringNew()
          .run(() -> artifacts.store(repository, path, blobId, sizeBytes, Instant.now()));
    } catch (RuntimeException raced) {
      if (!isDuplicateKey(raced)) {
        throw raced;
      }
      // Somebody else pulled the same coordinate through first. That is the idempotent outcome this
      // method promises, and it is a debug line rather than a warning: on a cache serving parallel
      // builds it is ordinary traffic, not an incident.
      LOG.debugf("maven proxy: %s in '%s' was recorded by a concurrent pull", path, repository);
    }
  }

  /**
   * Whether a failure is "that row is already there" and nothing else.
   *
   * <p>Read off the SQLSTATE rather than off an exception type, because the type depends on the
   * dialect while {@code 23505} (and {@code 23000}, which older drivers report for the same thing)
   * is what the standard says a unique violation is. Anything else — a dead connection, a column
   * that does not fit — is not this method's to absorb and is rethrown, because a cache that
   * silently swallowed a failed write would go on serving misses forever with nothing to say why.
   */
  private static boolean isDuplicateKey(Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && ("23505".equals(sql.getSQLState()) || "23000".equals(sql.getSQLState()))) {
        return true;
      }
      if (cause.getCause() == cause) {
        return false;
      }
    }
    return false;
  }

  /**
   * Throws away a cached file that could not be served, so the request that found it broken can pull
   * it through again.
   *
   * <p><b>The self-heal's door, and deliberately not {@link #evictProxiedArtifact}'s.</b> The two
   * differ in what they can be trusted to assume. The sweep's door works from a plan it built by
   * enumerating rows, so loading one back and deleting the entity is right there. This one is
   * reached because a row would not serve — which means a load is the very thing that cannot be
   * relied on. It deletes on the predicate and reports how many rows went; more than one is the fault
   * itself, since the table's primary key is {@code (repository, path)}.
   *
   * <p><b>The type is checked, not assumed</b>, for {@link #evictProxiedArtifact}'s reason and more
   * sharply: one table holds both maven types, and a self-heal that fired on a hosted repository
   * would silently delete a jar this platform published because its blob happened to be unreadable
   * for a second. A hosted miss has a 404 and no upstream to ask; there is nothing to heal and
   * nothing may be removed. So this refuses anything that is not a {@code maven-proxy} — and
   * refuses by returning <b>zero</b> rather than by throwing, because the caller is a route already
   * handling a failure and a second exception on top of the first buries the one worth reading.
   *
   * @return how many rows were removed; {@code 0} means nothing was dropped, for any reason
   */
  @ActivateRequestContext
  @Transactional
  public long dropUnservableCachedFile(String repository, String path) {
    ArtifactRepository row = repository == null ? null : repositories.findById(repository);
    if (row == null || !MavenProxyProfile.KEY.equals(row.type)) {
      return 0;
    }
    return artifacts.deleteEveryRowAt(repository, path);
  }

  @ActivateRequestContext
  public Optional<CachedMetadata> findProxyMetadata(String repository, String path) {
    return metadata
        .findOne(repository, path)
        .map(row -> new CachedMetadata(row.doc, row.etag, row.lastModified, row.fetchedAt));
  }

  /** Stores or replaces a cached metadata document. Upstream's document goes in verbatim. */
  @ActivateRequestContext
  @Transactional
  public void storeProxyMetadata(
      String repository,
      String path,
      String doc,
      String etag,
      String lastModified,
      Instant fetchedAt) {
    MavenProxyMetadata row =
        metadata.findOne(repository, path).orElseGet(MavenProxyMetadata::new);
    boolean fresh = row.path == null;
    row.repository = repository;
    row.path = path;
    row.doc = doc;
    row.etag = etag;
    row.lastModified = lastModified;
    row.fetchedAt = fetchedAt;
    if (fresh) {
      metadata.persist(row);
    }
  }

  /**
   * Marks a cached document as revalidated without rewriting it — what a {@code 304} from upstream
   * means.
   *
   * <p>A bulk update rather than a load-and-mutate, for the reason {@code
   * NpmRegistryService.touchProxyMetadata}'s twin gives: loading the row to move one timestamp
   * drags the whole CLOB through the JVM to write eight bytes.
   */
  @ActivateRequestContext
  @Transactional
  public void touchProxyMetadata(
      String repository, String path, String etag, String lastModified, Instant fetchedAt) {
    metadata.update(
        "fetchedAt = ?1, etag = ?2, lastModified = ?3 where repository = ?4 and path = ?5",
        fetchedAt, etag, lastModified, repository, path);
  }

  /**
   * Evicts one <b>cached</b> file of a proxy repository.
   *
   * <p>The twin of {@link #collect}, and the difference is what it is allowed to mean. A hosted
   * collection removes a coordinate this platform published; here the file is upstream's, evicting
   * it is a cache decision, and the very next resolve must be able to pull it through again.
   *
   * <p><b>The repository's type is checked, not assumed.</b> {@code maven_artifact} is one table for
   * both maven types, so a path is all that separates a cached row from a deployed one, and a caller
   * that got the type wrong would silently delete a published jar through the cache's door.
   *
   * <p>Package-private, reached only through {@link MavenRegistryCollection}, and called only by the
   * {@code gc} module's {@code MavenProxyGcAdapter}. The blob is not touched; what may be unlinked
   * is the sweep's question.
   *
   * @throws MavenException {@code 404} if there is no such row, {@code 409} if the repository is not
   *     a proxy
   */
  @ActivateRequestContext
  @Transactional
  void evictProxiedArtifact(String repository, String path) {
    requireProxy(repository, path);
    MavenArtifact row =
        artifacts
            .findOne(repository, path)
            .orElseThrow(() -> new MavenException(404, "no such cached path " + path + " to evict"));
    artifacts.delete(row);
  }

  /**
   * Evicts one cached {@code maven-metadata.xml}. Same door, same type check: the next request
   * revalidates against upstream and caches the answer again.
   *
   * @throws MavenException {@code 404} if nothing is cached, {@code 409} if the repository is not a
   *     proxy
   */
  @ActivateRequestContext
  @Transactional
  void evictProxiedMetadata(String repository, String path) {
    requireProxy(repository, path);
    MavenProxyMetadata row =
        metadata
            .findOne(repository, path)
            .orElseThrow(
                () -> new MavenException(404, "no cached metadata at " + path + " to evict"));
    metadata.delete(row);
  }

  /** Refuses anything but a {@code maven-proxy} repository — see {@link #evictProxiedArtifact}. */
  private void requireProxy(String repository, String path) {
    ArtifactRepository row = repository == null ? null : repositories.findById(repository);
    if (row == null || !MavenProxyProfile.KEY.equals(row.type)) {
      throw new MavenException(
          409,
          "refusing to evict "
              + path
              + " from '"
              + repository
              + "' — eviction is a cache operation, and this repository is "
              + (row == null ? "not registered" : RepositoryTypeProfile.wireNameOf(row.type))
              + ". A deployed file leaves only through collect(), whose caller removes a whole"
              + " coordinate rather than one path");
    }
  }

  /**
   * Every row under a metadata prefix, for the derived document.
   *
   * <p>A prefix scan on the primary key's leading columns — an index read at this store's scale
   * (the platform holds dozens of artifacts, not Maven Central's millions).
   */
  @ActivateRequestContext
  public List<StoredPath> listUnder(String repository, String prefix) {
    return artifacts.listPathsAndCreatedAtStartingWith(repository, prefix).stream()
        .map(row -> new StoredPath((String) row[0], (Instant) row[1]))
        .toList();
  }
}
