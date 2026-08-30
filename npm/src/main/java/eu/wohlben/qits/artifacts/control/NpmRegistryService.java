package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.NpmVersionTombstone;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.error.NpmException;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionTombstoneRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The npm registry's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, for the same load-bearing reason {@code
 * OciRegistryService}'s are: the callers are raw Vert.x route handlers, which run with <b>no CDI
 * request context and no transaction</b>. Drop an annotation and the npm routes fail with {@code
 * ContextNotActiveException} at runtime only, with a green {@code mvn verify} behind them.
 *
 * <p>Tarball bytes stay deliberately outside all of it — they are staged into {@code BlobStore} by
 * the route before anything here is called, so a slow publish or a slow upstream fetch cannot time
 * a transaction out.
 *
 * <p>Nothing here returns an entity. The route layer runs outside the persistence context, so a
 * lazily-materialised document column reached from there would fail; every accessor below copies what
 * it read into a record while the context is still active.
 */
@ApplicationScoped
public class NpmRegistryService {

  /**
   * The one dist-tag with an ordering rule. It is npm's default for a bare {@code npm install
   * <name>} and for a bare {@code npm publish}, which is exactly what makes it the foot-gun: a
   * publish that names no tag names this one.
   */
  private static final String LATEST = "latest";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject NpmVersionRepository versions;
  @Inject NpmDistTagRepository distTags;
  @Inject NpmProxyPackumentRepository packuments;
  @Inject NpmVersionTombstoneRepository tombstones;
  @Inject NpmAccessTracker accessTracker;

  /** A stored version, flattened for packument assembly and for serving its tarball. */
  public record StoredVersion(
      String version, String tarballBlobId, String integrity, String shasum, String manifestJson) {}

  /** A cached upstream packument: the document verbatim, its validator, and when it arrived. */
  public record CachedPackument(String doc, String etag, Instant fetchedAt) {}

  /**
   * Resolves the first path segment after {@code /artifacts/npm/} to an npm-typed repository row.
   *
   * <p>Repositories are not created implicitly, exactly as on {@code /v2}: an unknown or
   * wrong-typed name is a 404 whose message names the ensure endpoint and the type to ask for. The
   * two seeded rows — {@code npm} (hosted) and {@code npmjs} (proxy) — mean a fresh deployment
   * needs no manual step for the platform's own convention; every other name still has to be asked
   * for, so a typo fails loudly rather than quietly minting a namespace.
   *
   * <p>Wrapped in {@link DbRetry#call} because it is the first database touch of every npm read and
   * every publish: without it a postgres cutover answers "no such npm repository" for a repository
   * that exists, and an npm client acts on that 404. A plain read, and the caller is a raw route
   * handler that opens no transaction.
   *
   * @return which of the two npm types this repository is, since almost every caller branches on it
   */
  @ActivateRequestContext
  public String requireNpmRepository(String name) {
    return DbRetry.call(
        "npm repository lookup for " + name,
        () -> {
          ArtifactRepository repository = name == null ? null : repositories.findById(name);
          if (repository == null
              || (!NpmPackagesProfile.KEY.equals(repository.type)
                  && !NpmProxyProfile.KEY.equals(repository.type))) {
            throw new NpmException(
                404,
                "no such npm repository '"
                    + name
                    + "'; create it with PUT /artifacts/api/repositories/"
                    + name
                    + " {\"type\":\"npm-packages\"} (or \"npm-proxy\")");
          }
          return repository.type;
        });
  }

  @ActivateRequestContext
  public Optional<StoredVersion> findVersion(String repository, String packageName, String version) {
    return versions.findOne(repository, packageName, version).map(NpmRegistryService::flatten);
  }

  /**
   * Records that a tarball GET served this version — the npm half of the access basis both settled
   * GC strategies read (artifacts-gc-plan.md, "Settlement").
   *
   * <p>Called for both repository types, because {@code npm_version} is one table for both and the
   * tarball route is one code path. Coalesced to one write per row per hour inside {@link
   * NpmAccessTracker}, so the hottest read npm has costs an indexed no-op update.
   */
  @ActivateRequestContext
  public void touchVersion(String repository, String packageName, String version) {
    accessTracker.touchNpmVersion(
        repository, packageName, version, Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  /** Every version of one package — the whole read side of packument assembly. */
  @ActivateRequestContext
  public List<StoredVersion> listVersions(String repository, String packageName) {
    return versions.listVersions(repository, packageName).stream()
        .map(NpmRegistryService::flatten)
        .toList();
  }

  /** The package's dist-tags, in a map whose iteration order is stable across requests. */
  @ActivateRequestContext
  public Map<String, String> distTags(String repository, String packageName) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (NpmDistTag tag : distTags.listTags(repository, packageName)) {
      tags.put(tag.tag, tag.version);
    }
    return tags;
  }

  /**
   * Writes one published version and moves the dist-tags that named it.
   *
   * <p>The immutability check lives here rather than in the route because it has to be inside the
   * same transaction as the insert — checking outside it would make two concurrent publishes of the
   * same version a race that both sides win.
   *
   * <p>One of those tags is guarded: {@code latest} may not move backwards. See {@link
   * #requireLatestMayMoveTo}, including why that refusal takes the whole publish with it.
   *
   * <p>Immutability is checked <b>twice</b>, against the row and against the tombstone, because
   * garbage collection makes "there is no row" mean two different things. See {@link #collect}.
   *
   * @throws NpmException {@code 403} if the version already exists, if it was collected, or if the
   *     publish would move {@code latest} to a version sorting below the one it names
   */
  @ActivateRequestContext
  @Transactional
  public void publish(
      String repository,
      String packageName,
      String version,
      String tarballBlobId,
      String integrity,
      String shasum,
      String manifestJson,
      Map<String, String> tagsToMove) {
    if (versions.findOne(repository, packageName, version).isPresent()) {
      throw new NpmException(
          403,
          "cannot publish over the existing "
              + packageName
              + "@"
              + version
              + " — published versions are immutable; bump the version");
    }
    // A collected version has no row, so the check above would wave it through as a fresh publish.
    // Its own message matters as much as its refusal: "immutable" would send a pusher looking for a
    // version they can see, and there is nothing to see.
    tombstones
        .findOne(repository, packageName, version)
        .ifPresent(
            collected -> {
              throw new NpmException(
                  403,
                  "cannot publish "
                      + packageName
                      + "@"
                      + version
                      + " — that version was published here and later removed by garbage collection"
                      + " on "
                      + collected.collectedAt
                      + "; a version name is never reused, even after its bytes are gone. Bump the"
                      + " version");
            });
    versions.persist(
        row(repository, packageName, version, tarballBlobId, integrity, shasum, manifestJson));
    tagsToMove.forEach((tag, target) -> moveTag(repository, packageName, tag, target));
  }

  /**
   * Deletes one published version and leaves its identity behind — the only way a version ever
   * leaves this registry.
   *
   * <p><b>Package-private, reached only through {@link NpmRegistryCollection}</b> and called only
   * by the {@code gc} module's {@code NpmPackagesGcStrategy.apply} — the same shape and the same
   * reason as {@code BlobStore.delete}: the two guarantees below hold only while there is one way
   * in, and a {@code public} {@code collect} would put an unpublish within reach of every route the
   * registry's {@code 405} exists to refuse.
   *
   * <p>The two guarantees:
   *
   * <ul>
   *   <li><b>The row and the tombstone move together</b>, in one transaction. A delete that
   *       committed without its tombstone would silently re-open the version's name for a publish
   *       carrying different bytes — the mutability the 403 exists to refuse.
   *   <li><b>A version a dist-tag names is refused.</b> Nothing here moves a tag: a dist-tag
   *       pointing at a deleted version is a packument whose {@code dist-tags} names a version its
   *       {@code versions} does not contain, which every npm client reads as a broken package. The
   *       strategy already never condemns such a version; this makes that a property of the
   *       mechanism rather than of the policy that happens to drive it.
   * </ul>
   *
   * <p>The tarball blob is <em>not</em> touched. Blobs dedupe across every repository type, so what
   * may be unlinked is never one type's question — the sweep answers it, from the census.
   *
   * @throws NpmException {@code 409} if a dist-tag still names the version
   */
  @ActivateRequestContext
  @Transactional
  void collect(String repository, String packageName, String version) {
    NpmVersion row =
        versions
            .findOne(repository, packageName, version)
            .orElseThrow(
                () ->
                    new NpmException(
                        404, "no such version " + packageName + "@" + version + " to collect"));
    for (NpmDistTag tag : distTags.listTags(repository, packageName)) {
      if (tag.version.equals(version)) {
        throw new NpmException(
            409,
            "refusing to collect "
                + packageName
                + "@"
                + version
                + " — the "
                + tag.tag
                + " dist-tag names it, and a dist-tag pointing at a version the packument no longer"
                + " lists is a broken package");
      }
    }
    NpmVersionTombstone tombstone = new NpmVersionTombstone();
    tombstone.repository = repository;
    tombstone.packageName = packageName;
    tombstone.version = version;
    tombstone.tarballBlobId = row.tarballBlobId;
    tombstone.collectedAt = Instant.now();
    tombstones.persist(tombstone);
    versions.delete(row);
  }

  /**
   * Evicts one <b>cached</b> version of a proxy repository — the row goes and <b>no tombstone is
   * written</b>.
   *
   * <p>The missing tombstone is the whole difference from {@link #collect}, and it is the point
   * rather than a shortcut. A tombstone records "this name was published here and its bytes are
   * gone, so the name is spent forever" — the immutability guarantee a hosted registry owes its
   * consumers. A proxy owes the opposite: the version is upstream's, evicting it is a cache
   * decision, and the very next {@code npm install} must be able to pull the same version through
   * again and re-cache it. A tombstone here would turn an eviction into an unpublish of somebody
   * else's package.
   *
   * <p><b>The repository's type is checked, not assumed.</b> {@code npm_version} is one table for
   * both npm types, so a coordinate is all that separates a cached row from a published one, and a
   * caller that got the type wrong would silently strip a published version of its tombstone. The
   * refusal is what makes "no tombstone" safe to say out loud.
   *
   * <p>Dist-tags are not consulted: a proxy has no {@code npm_dist_tag} rows — its packument is
   * upstream's document verbatim — so there is no pointer here to break.
   *
   * <p>Package-private, reached only through {@link NpmRegistryCollection}, and called only by the
   * {@code gc} module's {@code NpmProxyGcAdapter}. The tarball blob is not touched; what may be
   * unlinked is the sweep's question, from the census.
   *
   * @throws NpmException {@code 404} if there is no such row, {@code 409} if the repository is not
   *     a proxy
   */
  @ActivateRequestContext
  @Transactional
  void evictProxiedVersion(String repository, String packageName, String version) {
    requireProxy(repository, packageName + "@" + version);
    NpmVersion row =
        versions
            .findOne(repository, packageName, version)
            .orElseThrow(
                () ->
                    new NpmException(
                        404, "no such version " + packageName + "@" + version + " to evict"));
    versions.delete(row);
  }

  /**
   * Evicts one cached packument document. Same door, same type check, same reason for writing no
   * tombstone: the next request revalidates against upstream and caches the answer again.
   *
   * <p>Deleting the document while cached versions of the package survive is a supported state and
   * not a repair case — the packument is re-fetched on the next read, and the tarball rows go on
   * serving from disk meanwhile.
   *
   * @throws NpmException {@code 404} if nothing is cached, {@code 409} if the repository is not a
   *     proxy
   */
  @ActivateRequestContext
  @Transactional
  void evictProxiedPackument(String repository, String packageName) {
    requireProxy(repository, packageName);
    NpmProxyPackument cached =
        packuments
            .findOne(repository, packageName)
            .orElseThrow(
                () ->
                    new NpmException(
                        404, "no cached packument for " + packageName + " to evict"));
    packuments.delete(cached);
  }

  /** Refuses anything but an {@code npm-proxy} repository — see {@link #evictProxiedVersion}. */
  private void requireProxy(String repository, String coordinate) {
    ArtifactRepository row = repository == null ? null : repositories.findById(repository);
    if (row == null || !NpmProxyProfile.KEY.equals(row.type)) {
      throw new NpmException(
          409,
          "refusing to evict "
              + coordinate
              + " from '"
              + repository
              + "' — eviction without a tombstone is a cache operation, and this repository is "
              + (row == null ? "not registered" : RepositoryTypeProfile.wireNameOf(row.type))
              + ". A published version leaves only through collect(), which writes the tombstone"
              + " that keeps its name from being reused");
    }
  }

  /**
   * Records a version the proxy just pulled through, if it is not already known.
   *
   * <p>Written lazily on the first tarball fetch rather than when a packument is cached, so the
   * tarball route is <b>one</b> code path for both repository types: look the version up, and if it
   * is missing and this is a proxy, go and get it. Idempotent, because two concurrent installs of
   * the same dependency are the normal case rather than the edge.
   */
  @ActivateRequestContext
  @Transactional
  public void recordProxiedVersion(
      String repository,
      String packageName,
      String version,
      String tarballBlobId,
      String integrity,
      String shasum,
      String manifestJson) {
    if (versions.findOne(repository, packageName, version).isPresent()) {
      return;
    }
    versions.persist(
        row(repository, packageName, version, tarballBlobId, integrity, shasum, manifestJson));
  }

  @ActivateRequestContext
  public Optional<CachedPackument> findProxyPackument(String repository, String packageName) {
    return packuments
        .findOne(repository, packageName)
        .map(cached -> new CachedPackument(cached.doc, cached.etag, cached.fetchedAt));
  }

  /** Stores or replaces a cached packument. Upstream's document goes in verbatim; see the entity. */
  @ActivateRequestContext
  @Transactional
  public void storeProxyPackument(
      String repository, String packageName, String doc, String etag, Instant fetchedAt) {
    NpmProxyPackument cached =
        packuments.findOne(repository, packageName).orElseGet(NpmProxyPackument::new);
    boolean fresh = cached.packageName == null;
    cached.repository = repository;
    cached.packageName = packageName;
    cached.doc = doc;
    cached.etag = etag;
    cached.fetchedAt = fetchedAt;
    if (fresh) {
      packuments.persist(cached);
    }
  }

  /**
   * Marks a cached packument as revalidated without rewriting its document — what a {@code 304} from
   * upstream means.
   *
   * <p>A bulk update rather than a load-and-mutate, and that is the point rather than an
   * optimisation detail: loading the row to move one timestamp would drag the whole packument CLOB
   * through the JVM on every TTL expiry, which for a popular package is a megabyte read to write
   * eight bytes. Separate from {@link #storeProxyPackument} for the same reason.
   */
  @ActivateRequestContext
  @Transactional
  public void touchProxyPackument(
      String repository, String packageName, String etag, Instant fetchedAt) {
    if (etag == null || etag.isBlank()) {
      packuments.update(
          "fetchedAt = ?1 where repository = ?2 and packageName = ?3",
          fetchedAt, repository, packageName);
      return;
    }
    packuments.update(
        "fetchedAt = ?1, etag = ?2 where repository = ?3 and packageName = ?4",
        fetchedAt, etag, repository, packageName);
  }

  private void moveTag(String repository, String packageName, String tag, String version) {
    NpmDistTag row = distTags.findOne(repository, packageName, tag).orElseGet(NpmDistTag::new);
    boolean fresh = row.tag == null;
    if (!fresh && LATEST.equals(tag)) {
      requireLatestMayMoveTo(packageName, row.version, version);
    }
    row.repository = repository;
    row.packageName = packageName;
    row.tag = tag;
    row.version = version;
    row.updatedAt = Instant.now();
    if (fresh) {
      distTags.persist(row);
    }
  }

  /**
   * The {@code latest} rule: it may never name a version sorting below the one it names today.
   *
   * <p>Why the registry enforces this rather than the pipelines: a bare {@code npm publish} means
   * {@code --tag latest}, so a main build publishing {@code <release>-main.g<sha>} would move {@code
   * latest} onto a prerelease and every consumer installing without a range would get a main build.
   * Convention in a pipeline file nobody lints is not a guard; this is, and it protects every future
   * pipeline rather than the ones that exist today.
   *
   * <p>Three edges, all deliberate:
   *
   * <ul>
   *   <li><b>Only {@code latest}.</b> Every other dist-tag — {@code main}, {@code next}, whatever a
   *       repository invents — moves anywhere, which is the whole point of having them.
   *   <li><b>Equal is allowed.</b> Re-naming the version {@code latest} already names is a no-op, and
   *       a real republish dies on version immutability long before it reaches here.
   *   <li><b>Unparseable is refused, in either direction.</b> This platform publishes valid semver
   *       only, and a version that cannot be ordered cannot be proved not to be a step backwards.
   *       Refusing says so; passing it through would be the silent failure this exists to remove.
   * </ul>
   *
   * <p>The refusal aborts the whole publish, because it is thrown inside {@link #publish}'s
   * transaction — the same shape the immutability refusal has, and the same reason: a publish that
   * half-happened and answered 201 is the outcome nobody can debug. The pusher gets one npm error
   * naming both versions and the flag that makes the publish correct.
   *
   * @throws NpmException {@code 403} if the move is backwards or cannot be ordered
   */
  static void requireLatestMayMoveTo(String packageName, String current, String candidate) {
    Optional<NpmSemver> from = NpmSemver.parse(current);
    Optional<NpmSemver> to = NpmSemver.parse(candidate);
    if (from.isEmpty() || to.isEmpty()) {
      throw new NpmException(
          403,
          "cannot move the latest dist-tag of "
              + packageName
              + " from "
              + current
              + " to "
              + candidate
              + " — "
              + (from.isEmpty() ? current : candidate)
              + " is not a semver version, so the two cannot be ordered; this registry publishes"
              + " semver only");
    }
    if (to.get().compareTo(from.get()) >= 0) {
      return;
    }
    throw new NpmException(
        403,
        "cannot move the latest dist-tag of "
            + packageName
            + " back from "
            + current
            + " to "
            + candidate
            + " — latest only ever moves forward"
            + (to.get().isPrerelease()
                ? "; publish a prerelease under its own dist-tag instead (npm publish --tag main)"
                : "; publish a higher version, or move a dist-tag other than latest"));
  }

  private static NpmVersion row(
      String repository,
      String packageName,
      String version,
      String tarballBlobId,
      String integrity,
      String shasum,
      String manifestJson) {
    NpmVersion row = new NpmVersion();
    row.repository = repository;
    row.packageName = packageName;
    row.version = version;
    row.tarballBlobId = tarballBlobId;
    row.integrity = integrity;
    row.shasum = shasum;
    row.manifestJson = manifestJson;
    row.createdAt = Instant.now();
    return row;
  }

  private static StoredVersion flatten(NpmVersion row) {
    return new StoredVersion(
        row.version, row.tarballBlobId, row.integrity, row.shasum, row.manifestJson);
  }
}
