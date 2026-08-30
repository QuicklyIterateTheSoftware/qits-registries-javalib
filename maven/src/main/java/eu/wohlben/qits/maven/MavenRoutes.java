package eu.wohlben.qits.maven;

import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.MavenChecksums;
import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.control.MavenRegistryService;
import eu.wohlben.qits.artifacts.error.MavenException;
import eu.wohlben.qits.registry.BlobSender;
import eu.wohlben.qits.registry.OciRequestBody;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The maven repository wire, at {@code /artifacts/maven/<repository>/<path…>}.
 *
 * <p>The npm shape verbatim: npm lives at {@code /artifacts/npm/npm/<pkg>}, so maven lives at
 * {@code /artifacts/maven/maven/<group/path>/<artifact>/<version>/<file>}. The first segment is the
 * {@code artifact_repository} row, the same first-segment rule both other protocol surfaces use,
 * and the segment itself is a literal in the code — no config key moves it, and {@code
 * MavenRegistryTest} is the only thing that would notice it drifting.
 *
 * <p>The server is a <b>dumb path store</b>. {@code mvn deploy} PUTs the jar, the pom, then each
 * file's checksums, then a merged {@code maven-metadata.xml} with its own checksums — one request
 * per file, no session, no lock. Resolution is GETs of the same paths. The server's whole
 * intelligence is three derivations and three rules:
 *
 * <ul>
 *   <li>{@code maven-metadata.xml} is <b>derived</b> per request from {@code maven_artifact} rows
 *       and never stored — the packument precedent, so it cannot become a second source of truth.
 *       The client's own metadata PUT is accepted and discarded, because refusing would break
 *       {@code mvn deploy} on its final request and storing it would serve a stale merge.
 *   <li>Checksums are <b>derived at GET</b> and <b>verified at PUT</b>, never stored — a PUT
 *       checksum that does not match the blob is a {@code 400}, the npm {@code
 *       requireClaimMatches} restated: a blob that does not hash to its name is not a blob.
 *   <li>Release paths and timestamped snapshot files are <b>immutable</b>: a re-PUT of identical
 *       bytes is an idempotent {@code 201}, a re-PUT of different bytes is a {@code 403} naming
 *       the rule. A literal {@code -SNAPSHOT} filename is the one mutable path, and the
 *       version-level {@code maven-metadata.xml} of a snapshot directory is derived from the
 *       timestamped filenames — the whole of the server's snapshot machinery, because the client
 *       computes the timestamped names. A snapshot directory with nothing but literal files
 *       answers 404 there, so the resolver's defined fallback survives.
 * </ul>
 *
 * <p>A {@code maven-proxy} repository runs on these same routes and inverts two of those three
 * rules, because nothing it holds is ours. {@code maven-metadata.xml} is <b>cached with a TTL</b>
 * rather than derived — the rows are what this cache happens to hold, not what exists upstream — and
 * a file's checksums are <b>upstream's own, cached</b> rather than derived, which is what keeps the
 * client's verification end to end. The one thing still derived there is the checksum of the cached
 * metadata document, and that is the point: it is computed from the bytes served beside it, so the
 * two can never disagree. A {@code PUT} is {@code 405} by type.
 *
 * <p><b>There is no authentication here, at all</b> — not a token, not a guard, nothing. The OCI
 * registry's threat model verbatim: producers and consumers are internal on qits-net, and from
 * outside {@code /artifacts/maven/**} falls under qits-gateway's ordinary session auth like any
 * other non-allowlisted artifacts path. Unlike npm's {@code ENEEDAUTH} pre-flight there is no
 * client-side ceremony either: maven sends no credential unless challenged, and this server never
 * challenges, so a pipeline's {@code distributionManagement} needs no matching {@code <server>}.
 */
@ApplicationScoped
public class MavenRoutes {

  private static final Logger LOG = Logger.getLogger(MavenRoutes.class);

  /** Waits for the NEXT chunk, not the whole upload — the registry's idle-timeout shape. */
  private static final Duration UPLOAD_IDLE_TIMEOUT = Duration.ofMinutes(1);

  @Inject MavenRegistryService registry;
  @Inject MavenUpstream upstream;
  @Inject BlobStore blobStore;
  @Inject BlobSender blobSender;

  /**
   * The one size answer for both directions a jar can travel, the npm {@code max-publish-size}
   * precedent: how large an artifact is this deployment willing to hold. Jars are megabytes at
   * most; the knob exists because the PUT streams and a raw stream is bounded by nothing else —
   * the git host's {@code max-pack-size} lesson.
   */
  @ConfigProperty(name = "qits.artifacts.maven.max-artifact-size", defaultValue = "128M")
  MemorySize maxArtifactSize;

  void init(@Observes Router router) {
    // HEAD is NOT derived from GET by Vert.x — every GET route needs its HEAD twin, or every client
    // that probes before downloading sees a 404.
    router
        .headWithRegex(MavenPaths.ARTIFACT)
        .blockingHandler(guarded("head artifact", rc -> serve(rc, false)));
    router
        .getWithRegex(MavenPaths.ARTIFACT)
        .blockingHandler(guarded("get artifact", rc -> serve(rc, true)));

    // The deploy PUT streams rather than buffers: a BodyHandler would hold the whole artifact in
    // memory, and a raw HttpServerRequest read is bounded by nothing — the two rules OciRequestBody
    // exists for. Hashing to sha256 happens inside the stage, for free, exactly as for npm tarballs
    // and OCI layers.
    router
        .putWithRegex(MavenPaths.ARTIFACT)
        .handler(OciRequestBody::pauseForWorker)
        .blockingHandler(guarded("deploy", this::deploy));

    // DELETE is deliberately unimplemented, exactly as on /v2 and /artifacts/npm: the store is
    // append-only and nothing should come to depend on undeploy semantics before they exist. 405
    // rather than 404, which would read as "unknown artifact".
    router
        .route(HttpMethod.DELETE, MavenPaths.BASE + "/*")
        .handler(
            rc ->
                MavenErrors.send(
                    rc, 405, "this maven repository does not implement undeploy"));

    // Everything else under the base is a short plain-text 404, never the SPA's HTML — a maven
    // client told 200 text/html reports anything but "no such path".
    router.route(MavenPaths.BASE).handler(this::notFound);
    router.route(MavenPaths.BASE + "/*").handler(this::notFound);
  }

  private void notFound(RoutingContext rc) {
    MavenErrors.send(rc, 404, "not a route this maven repository serves: " + rc.normalizedPath());
  }

  // --- GET / HEAD -------------------------------------------------------------------------------

  /**
   * {@code GET|HEAD /artifacts/maven/<repo>/<path…>} — an artifact, a derived document, or a
   * derived checksum. The three are told apart <b>by name</b>, in order: {@code
   * maven-metadata.xml}, its checksum siblings, then any other checksum suffix, then a stored file.
   *
   * <p>A {@code maven-proxy} repository takes a shorter route through the same names — see {@link
   * #serveProxied}. The two are told apart by the repository's type and never by a path.
   */
  private void serve(RoutingContext rc, boolean withBody) {
    String repository = rc.pathParam("repository");
    String type = registry.requireMavenRepository(repository);
    String path = rc.pathParam("path");
    String file = MavenLayout.fileOf(path);

    if (MavenProxyProfile.KEY.equals(type)) {
      serveProxied(rc, repository, path, file, withBody);
      return;
    }

    if (MavenLayout.isMetadata(file)) {
      serveDocument(rc, repository, path, null, withBody);
      return;
    }
    String metadataAlgorithm = MavenLayout.metadataChecksumAlgorithm(file);
    if (metadataAlgorithm != null) {
      serveDocument(
          rc, repository, MavenLayout.directoryOf(path) + "/" + MavenLayout.METADATA,
          metadataAlgorithm, withBody);
      return;
    }
    String algorithm = MavenLayout.checksumAlgorithm(file);
    if (algorithm != null) {
      serveChecksum(rc, repository, path, algorithm, withBody);
      return;
    }
    serveFile(rc, repository, path, withBody);
  }

  /**
   * The pull-through cache's read path: two classes of name, and no derivation of the third.
   *
   * <ul>
   *   <li>{@code maven-metadata.xml} is the one document that mutates upstream, so it is served from
   *       the TTL'd cache and revalidated on expiry ({@link MavenUpstream#metadata}). It is
   *       <b>not</b> derived from the cached rows the way the hosted repository derives its own: the
   *       rows are the versions this cache happens to hold, and a resolver asking what exists
   *       upstream would be told a subset and stop looking.
   *   <li>A metadata checksum sibling is <b>derived by hashing the cached document</b>, and that is
   *       the one place this proxy computes a hash rather than caching upstream's. Upstream's copy
   *       is a hash of whatever its metadata says <em>now</em>, which is a different document from
   *       the one inside our TTL the moment a version is released — so proxying it would hand every
   *       client a checksum that does not match the bytes beside it. A derived one is consistent by
   *       construction.
   *   <li>Everything else, <b>upstream's own {@code .sha1}/{@code .md5}/{@code .sha256}/{@code
   *       .sha512} files included</b>, is an immutable path: cached forever, served from the blob
   *       store, fetched once on a miss. Caching those rather than deriving them is what keeps the
   *       client's verification end to end — see {@link MavenUpstream}'s javadoc.
   * </ul>
   */
  private void serveProxied(
      RoutingContext rc, String repository, String path, String file, boolean withBody) {

    if (MavenLayout.isMetadata(file)) {
      byte[] document = upstream.metadata(repository, path);
      respond(rc, 200, "application/xml; charset=utf-8", document, withBody);
      return;
    }
    String metadataAlgorithm = MavenLayout.metadataChecksumAlgorithm(file);
    if (metadataAlgorithm != null) {
      byte[] document =
          upstream.metadata(
              repository, MavenLayout.directoryOf(path) + "/" + MavenLayout.METADATA);
      byte[] hex =
          MavenChecksums.hexDigest(document, metadataAlgorithm).getBytes(StandardCharsets.UTF_8);
      respond(rc, 200, "text/plain; charset=utf-8", hex, withBody);
      return;
    }

    MavenRegistryService.StoredArtifact stored =
        registry
            .findArtifact(repository, path)
            .orElseGet(() -> upstream.fetchArtifact(repository, path));
    serveStored(rc, repository, path, stored, withBody);
  }

  /**
   * The derived {@code maven-metadata.xml}, or its derived checksum. Derived per request from the
   * rows under the path's directory, so it can never disagree with what the store actually holds —
   * and a directory nothing is deployed under is a 404, not an empty document.
   */
  private void serveDocument(
      RoutingContext rc, String repository, String path, String checksumAlgorithm, boolean withBody) {
    String prefix = MavenLayout.directoryOf(path);
    String document =
        MavenLayout.isSnapshotVersion(MavenLayout.fileOf(prefix))
            ? deriveSnapshotMetadata(repository, prefix)
            : deriveArtifactMetadata(repository, prefix);
    if (checksumAlgorithm != null) {
      byte[] hex =
          MavenChecksums.hexDigest(document.getBytes(StandardCharsets.UTF_8), checksumAlgorithm)
              .getBytes(StandardCharsets.UTF_8);
      respond(rc, 200, "text/plain; charset=utf-8", hex, withBody);
      return;
    }
    respond(
        rc,
        200,
        "application/xml; charset=utf-8",
        document.getBytes(StandardCharsets.UTF_8),
        withBody);
  }

  /**
   * Artifact-level derivation: the distinct version directories under the prefix.
   *
   * <p>A version-level request for a release directory derives nothing and 404s, which is the
   * resolver's defined signal to fall back to the literal filename. Snapshot version directories
   * get their own document — {@link #deriveSnapshotMetadata}.
   */
  private String deriveArtifactMetadata(String repository, String prefix) {
    if (prefix.isEmpty()) {
      throw new MavenException(404, "no metadata at the repository root: " + prefix);
    }
    List<MavenRegistryService.StoredPath> rows = registry.listUnder(repository, prefix);
    Set<String> versions = new LinkedHashSet<>();
    Instant lastUpdated = null;
    for (MavenRegistryService.StoredPath row : rows) {
      String rest = row.path().substring(prefix.length() + 1);
      int slash = rest.indexOf('/');
      if (slash < 0) {
        // A file directly under the prefix is not a version directory; it contributes nothing.
        continue;
      }
      versions.add(rest.substring(0, slash));
      if (lastUpdated == null || row.createdAt().isAfter(lastUpdated)) {
        lastUpdated = row.createdAt();
      }
    }
    if (versions.isEmpty()) {
      throw new MavenException(404, "no metadata here: nothing is deployed under " + prefix);
    }
    String groupId = MavenLayout.directoryOf(prefix).replace('/', '.');
    String artifactId = MavenLayout.fileOf(prefix);
    return MavenMetadata.artifactDocument(
        groupId, artifactId, List.copyOf(versions), lastUpdated);
  }

  /**
   * Version-level derivation: the snapshot directory's timestamped filenames, read back into the
   * {@code <snapshotVersions>} a resolver maps the {@code -SNAPSHOT} coordinate through.
   *
   * <p>A directory holding <b>only</b> literal {@code -SNAPSHOT} files (a non-unique deploy) has
   * nothing to derive and answers <b>404, deliberately</b>: the resolver's defined fallback for a
   * missing version-level document is exactly that literal filename, and serving an empty document
   * would pre-empt the fallback with nothing in it.
   */
  private String deriveSnapshotMetadata(String repository, String prefix) {
    List<MavenRegistryService.StoredPath> rows = registry.listUnder(repository, prefix);
    String version = MavenLayout.fileOf(prefix);
    String artifactId = MavenLayout.fileOf(MavenLayout.directoryOf(prefix));
    String groupId = MavenLayout.directoryOf(MavenLayout.directoryOf(prefix)).replace('/', '.');

    List<MavenLayout.SnapshotFileName> files = new ArrayList<>();
    Instant lastUpdated = null;
    for (MavenRegistryService.StoredPath row : rows) {
      String file = row.path().substring(prefix.length() + 1);
      if (file.contains("/")) {
        continue;
      }
      MavenLayout.SnapshotFileName parsed =
          MavenLayout.parseTimestampedSnapshot(artifactId, version, file);
      if (parsed != null) {
        files.add(parsed);
        if (lastUpdated == null || row.createdAt().isAfter(lastUpdated)) {
          lastUpdated = row.createdAt();
        }
      }
    }
    if (files.isEmpty()) {
      throw new MavenException(
          404,
          "no timestamped snapshots under "
              + prefix
              + " — resolve the literal -SNAPSHOT filename instead");
    }
    return MavenMetadata.snapshotDocument(groupId, artifactId, version, files, lastUpdated);
  }

  /**
   * The derived checksum of a stored file — computed from the blob bytes at read time, never
   * stored: all four algorithms are one pass each at platform jar sizes, and a stored copy is a
   * second source of truth that can only ever disagree.
   */
  private void serveChecksum(
      RoutingContext rc, String repository, String path, String algorithm, boolean withBody) {
    String referenced = path.substring(0, path.length() - algorithm.length() - 1);
    String hex = checksumOf(repository, referenced, algorithm);
    respond(rc, 200, "text/plain; charset=utf-8", hex.getBytes(StandardCharsets.UTF_8), withBody);
  }

  /** A stored file of a hosted repository: a miss here is a 404, because there is nowhere to ask. */
  private void serveFile(RoutingContext rc, String repository, String path, boolean withBody) {
    MavenRegistryService.StoredArtifact stored =
        registry
            .findArtifact(repository, path)
            .orElseThrow(() -> new MavenException(404, "no such artifact: " + path));
    serveStored(rc, repository, path, stored, withBody);
  }

  /**
   * A stored file, streamed from the store — <b>one</b> code path for both maven types, which is the whole
   * reason a proxied file gets an ordinary {@code maven_artifact} row on its first pull. The only
   * difference the proxy makes is what happens on a miss: a hosted repository has a 404 to give, and
   * a proxy has an upstream to ask.
   *
   * <p>Immutable paths are content-addressed underneath and immutable on top, so the bytes behind
   * their URL can never mean something else — the same stance as the npm tarball route and the OCI
   * blob route. A literal {@code -SNAPSHOT} file is the one moving target, and says so in its cache
   * header; nothing a proxy caches is in that class, because upstream's mutable document never
   * becomes a row.
   */
  private void serveStored(
      RoutingContext rc,
      String repository,
      String path,
      MavenRegistryService.StoredArtifact stored,
      boolean withBody) {
    long size;
    try {
      size = blobStore.size(stored.blobId());
    } catch (Exception missing) {
      throw new MavenException(404, "the bytes of " + path + " are not stored");
    }

    // Size first, then touch — a row whose bytes are gone is a 404, not an access. HEAD counts,
    // the stance the OCI manifest route already takes. The derived documents and checksums do not:
    // they are not this row's bytes, and a resolver never fetches a checksum without its file.
    //
    // One call for both types. A cached file is an ordinary maven_artifact row, the fetch that
    // created it counts as its first access, and this is the column the cache eviction window is
    // measured against — so an untracked read here would be a dependency the collector thinks
    // nothing resolves.
    registry.touchArtifact(repository, path);

    HttpServerResponse response =
        rc.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size))
            .putHeader(HttpHeaders.ETAG, "\"" + stored.blobId() + "\"");
    if (MavenLayout.isMutablePath(path)) {
      // The one moving target: a literal -SNAPSHOT file may be redeployed, so the bytes behind this
      // URL CAN change. The ETag stays, so a revalidation is a cheap 304 when they have not.
      response.putHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    } else {
      // Content-addressed underneath and immutable on top, so the bytes behind this URL can never
      // mean something else — the same stance as the npm tarball route and the OCI blob route.
      response.putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
    }
    if (!withBody) {
      // HEAD must carry the same Content-Length as GET, and BlobSender writes a body
      // unconditionally, so it must not be reached here.
      response.end();
      return;
    }
    blobSender.send(response, stored.blobId(), "maven " + path);
  }

  // --- PUT --------------------------------------------------------------------------------------

  /**
   * {@code PUT /artifacts/maven/<repo>/<path…>} — the deploy. {@code mvn deploy} sends the jar, the
   * pom, each file's checksums, and finally its merged {@code maven-metadata.xml} with checksums;
   * each is answered per file.
   */
  private void deploy(RoutingContext rc) {
    String repository = rc.pathParam("repository");
    String type = registry.requireMavenRepository(repository);
    if (!MavenPackagesProfile.KEY.equals(type)) {
      // Refused by TYPE, not by configuration, and before the body is read: a cache that accepted a
      // deploy would let cached upstream content and published content share a namespace, which is
      // the one thing the two-type split exists to prevent. The same refusal npm's publish makes.
      throw new MavenException(
          405,
          "'"
              + repository
              + "' is a pull-through cache of an upstream maven repository and accepts no deploys; "
              + "deploy to a maven-packages repository instead");
    }
    String path = rc.pathParam("path");
    String file = MavenLayout.fileOf(path);

    // The client's own metadata is accepted and DISCARDED, at either level and with its checksums.
    // Refusing would break mvn deploy on its final request, after every artifact already landed;
    // storing it would serve a merge the client computed, which goes stale the moment a second
    // deploy lands — the exact second source of truth the derived document exists to prevent.
    if (MavenLayout.isMetadata(file) || MavenLayout.metadataChecksumAlgorithm(file) != null) {
      drain(rc);
      respond(rc, 201, "text/plain; charset=utf-8", "accepted\n".getBytes(StandardCharsets.UTF_8), true);
      return;
    }

    // A checksum PUT is VERIFIED, not stored: recomputed against the referenced artifact's blob and
    // refused on mismatch — the npm requireClaimMatches restated. A match stores nothing, because
    // the checksum is derivable.
    String algorithm = MavenLayout.checksumAlgorithm(file);
    if (algorithm != null) {
      verifyChecksum(rc, repository, path, algorithm);
      respond(rc, 201, "text/plain; charset=utf-8", "verified\n".getBytes(StandardCharsets.UTF_8), true);
      return;
    }

    MavenLayout.ArtifactPath parsed = MavenLayout.parse(path);
    if (parsed == null) {
      throw new MavenException(
          400,
          "not a maven artifact path: "
              + path
              + " — expected <group segments>/<artifact>/<version>/<file> with the file starting"
              + " with <artifact>-");
    }

    BlobStore.StagedBlob staged;
    try (InputStream body = OciRequestBody.open(rc, UPLOAD_IDLE_TIMEOUT.toMillis())) {
      staged = blobStore.stage(body, maxArtifactSize.asLongValue());
    } catch (IOException e) {
      throw new MavenException(400, "the upload stream failed: " + e.getMessage());
    }
    blobStore.promote(staged);
    registry.deploy(repository, parsed, staged.sha256(), staged.size());
    respond(rc, 201, "text/plain; charset=utf-8", "stored\n".getBytes(StandardCharsets.UTF_8), true);
  }

  /**
   * The checksum claim, checked against the blob and answered the npm way: a mismatch is a 400
   * naming both values. The referenced artifact must already be stored — the deploy plugin sends
   * the artifact before its checksums, so a missing one means the client got the order wrong.
   */
  private void verifyChecksum(
      RoutingContext rc, String repository, String path, String algorithm) {
    String referenced = path.substring(0, path.length() - algorithm.length() - 1);
    String claimed = readClaim(rc, algorithm);
    String computed = checksumOf(repository, referenced, algorithm);
    if (!claimed.equalsIgnoreCase(computed)) {
      throw new MavenException(
          400,
          "the bytes of "
              + referenced
              + " do not match their claimed "
              + algorithm
              + " (claimed "
              + claimed
              + ", computed "
              + computed
              + ")");
    }
  }

  /** The hex of a stored artifact's blob, or a 400 when there is no such artifact to verify against. */
  private String checksumOf(String repository, String referenced, String algorithm) {
    MavenRegistryService.StoredArtifact stored =
        registry
            .findArtifact(repository, referenced)
            .orElseThrow(
                () ->
                    new MavenException(
                        400, "no such artifact to take a checksum of: " + referenced));
    try (InputStream bytes = blobStore.open(stored.blobId())) {
      return MavenChecksums.hexDigest(bytes, algorithm);
    } catch (Exception missing) {
      throw new MavenException(404, "the bytes of " + referenced + " are not stored");
    }
  }

  /**
   * The claimed hex of a checksum PUT: a few dozen characters, read off the stream with a hard
   * bound so this route cannot be turned into the buffering one.
   */
  private String readClaim(RoutingContext rc, String algorithm) {
    byte[] bytes;
    try (InputStream body = OciRequestBody.open(rc, UPLOAD_IDLE_TIMEOUT.toMillis())) {
      bytes = body.readNBytes(512);
    } catch (IOException e) {
      throw new MavenException(400, "the upload stream failed: " + e.getMessage());
    }
    String claimed = new String(bytes, StandardCharsets.UTF_8).trim();
    if (claimed.isEmpty()) {
      throw new MavenException(400, "the " + algorithm + " PUT carried no checksum");
    }
    return claimed;
  }

  /**
   * Reads and discards a body that nothing will look at — the client metadata PUT. Staged through
   * the one write funnel so the cap applies to it too, then discarded: nothing is persisted.
   */
  private void drain(RoutingContext rc) {
    try (InputStream body = OciRequestBody.open(rc, UPLOAD_IDLE_TIMEOUT.toMillis())) {
      BlobStore.StagedBlob staged = blobStore.stage(body, maxArtifactSize.asLongValue());
      blobStore.discard(staged);
    } catch (IOException e) {
      throw new MavenException(400, "the upload stream failed: " + e.getMessage());
    }
  }

  private void respond(
      RoutingContext rc, int status, String contentType, byte[] body, boolean withBody) {
    HttpServerResponse response =
        rc.response()
            .setStatusCode(status)
            .putHeader(HttpHeaders.CONTENT_TYPE, contentType)
            .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length));
    if (!withBody) {
      response.end();
      return;
    }
    response.end(Buffer.buffer(body));
  }

  /**
   * Wraps a handler so every throwable becomes the plain-text envelope rather than {@code
   * QuarkusErrorHandler}'s HTML.
   */
  private Handler<RoutingContext> guarded(String what, Handler<RoutingContext> handler) {
    return rc -> {
      try {
        handler.handle(rc);
      } catch (Throwable thrown) {
        MavenErrors.fail(rc, what, thrown);
      }
    };
  }
}
