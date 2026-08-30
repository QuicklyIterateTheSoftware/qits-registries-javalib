package eu.wohlben.qits.registry;

import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.OciDigest;
import eu.wohlben.qits.artifacts.control.OciImageName;
import eu.wohlben.qits.artifacts.control.OciManifestParser;
import eu.wohlben.qits.artifacts.control.OciRegistryService;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The OCI Distribution API, at the literal {@code /v2} — root-level, beside {@code /artifacts/*}.
 *
 * <p>The mount point is not a choice. Docker and podman resolve {@code <host>/<name>:<tag>} against
 * {@code <host>/v2/} and accept no path prefix, so JAX-RS — which lives under {@code
 * quarkus.rest.path} — cannot serve this, and the segment is a literal in the code exactly as {@code
 * GitHostRoutes.BASE} is. No config key moves either of them, and no JAX-RS test would notice if one
 * drifted; {@code RegistryTest} is the only thing that would.
 *
 * <p><b>Unlike {@code GitHostRoutes}, no {@link BodyHandler} on the blob routes.</b> A layer is up to
 * a gigabyte and must stream chunk by chunk into the store rather than into a Buffer; see {@link
 * OciRequestBody} for the two rules that makes mandatory, and {@link BlobSender} for the same
 * discipline on the way back out. The manifest routes do buffer, deliberately: a manifest is small
 * JSON that has to be digested and parsed as a whole, and is capped far below the wire ceiling.
 *
 * <p><b>Two kinds of namespace answer on these routes.</b> A hosted {@code oci-images} repository is
 * the original path, unchanged. A registered mirror namespace ({@code oci-mirror}) serves cached
 * upstream content from the same rows through the same handlers — the difference is in three places
 * only: a read resolves through {@code OciRegistryService.resolveForPull}, which consults the
 * upstream table; a read that finds nothing goes through {@link MirrorUpstream}, which fetches it,
 * verifies it and binds it; and every write refuses by type with {@code 405}.
 *
 * <p>Those two mirror lines are the <b>whole</b> of the miss path's footprint on this file, and that
 * is the point: a hit — every pull of anything already cached, and every pull of every hosted
 * repository — runs the code that was here before, with no upstream contact and no new way to fail.
 *
 * <p>No handler here calls {@code rc.fail()}. Quarkus installs {@code QuarkusErrorHandler} as the
 * router's failure handler and it answers in a shape no registry client can read, so every handler
 * catches and writes its own response — the discipline {@code GitHostRoutes.fail()} already follows.
 */
@ApplicationScoped
public class RegistryRoutes {

  private static final Logger LOG = Logger.getLogger(RegistryRoutes.class);

  private static final String DOCKER_CONTENT_DIGEST = "Docker-Content-Digest";
  private static final String DOCKER_UPLOAD_UUID = "Docker-Upload-Uuid";

  @Inject OciRegistryService registry;
  @Inject MirrorUpstream mirror;
  @Inject OciUploadSessions uploads;
  @Inject OciManifestParser manifestParser;
  @Inject BlobStore blobStore;
  @Inject BlobSender blobSender;

  @ConfigProperty(name = "qits.artifacts.oci.max-layer-size", defaultValue = "1G")
  MemorySize maxLayerSize;

  @ConfigProperty(name = "qits.artifacts.oci.max-manifest-size", defaultValue = "4M")
  MemorySize maxManifestSize;

  @ConfigProperty(name = "qits.artifacts.oci.upload-idle-timeout", defaultValue = "PT1M")
  Duration uploadIdleTimeout;

  void init(@Observes Router router) {
    // 1. The API-version header first, from one place, so no route added later can forget it —
    //    clients use it to decide they are talking to a v2 registry at all. Both spellings:
    //    normalizedPath() keeps a trailing slash.
    //
    //    There is NO write guard here, and that is a decision, not an omission. The registry used
    //    to demand a static token as an HTTP Basic password on writes (the RegistryAuthGuard this
    //    handler replaced); it is gone because both directions it guarded resolved elsewhere:
    //    on qits-net, producers are trusted (the platform's own posture, and what makes an
    //    automated image publisher need no credential store), and from outside, qits-gateway keeps
    //    /v2 write methods OFF its token-free allowlist — an internet docker push is challenged
    //    for a session it cannot hold and dies there. That gateway rule is now this registry's
    //    whole external write protection: re-allowlisting /v2 writes at the gateway without
    //    restoring a guard here would open push to the internet, and the gateway's PublicPathsTest
    //    says so in so many words.
    //
    //    These are also raw Vert.x routes, so the JAX-RS AdminWriteGuard never sees them: turning
    //    the machine-token gate on guards the JSON admin API and leaves /v2 exactly as it is
    //    (qits-platform-idp phase 1, and RegistryOpenPushTest pins it). A docker client speaks no bearer
    //    from qits-platform-idp, which is why guarding this surface is its own decision and not this one.
    router.route(RegistryPaths.BASE).handler(RegistryRoutes::stampApiVersion);
    router.route(RegistryPaths.BASE + "/*").handler(RegistryRoutes::stampApiVersion);

    // 2. The version probe. Docker sends "/v2/"; a curl check usually sends "/v2".
    router.get(RegistryPaths.BASE).handler(this::version);
    router.get(RegistryPaths.BASE + "/").handler(this::version);

    // 3. Blob uploads. The trailing slash is optional because clients differ on it.
    router
        .postWithRegex(RegistryPaths.UPLOADS)
        .handler(OciRequestBody::pauseForWorker)
        .blockingHandler(guarded("start upload", this::startUpload));
    router
        .patchWithRegex(RegistryPaths.UPLOAD_SESSION)
        .handler(OciRequestBody::pauseForWorker)
        .blockingHandler(guarded("patch upload", this::patchUpload));
    router
        .putWithRegex(RegistryPaths.UPLOAD_SESSION)
        .handler(OciRequestBody::pauseForWorker)
        .blockingHandler(guarded("finish upload", this::finishUpload));
    router
        .getWithRegex(RegistryPaths.UPLOAD_SESSION)
        .blockingHandler(guarded("upload status", this::uploadStatus));

    // 4. Blobs. HEAD is not derived from GET by Vert.x — it needs its own route, and docker asks
    //    HEAD before every upload, so getting it wrong means re-uploading every layer every time.
    router.headWithRegex(RegistryPaths.BLOB).blockingHandler(guarded("head blob", rc -> serveBlob(rc, false)));
    router.getWithRegex(RegistryPaths.BLOB).blockingHandler(guarded("get blob", rc -> serveBlob(rc, true)));

    // 5. Manifests. The PUT is the one route that buffers, capped well under the wire ceiling.
    router
        .headWithRegex(RegistryPaths.MANIFEST)
        .blockingHandler(guarded("head manifest", rc -> serveManifest(rc, false)));
    router
        .getWithRegex(RegistryPaths.MANIFEST)
        .blockingHandler(guarded("get manifest", rc -> serveManifest(rc, true)));
    router
        .putWithRegex(RegistryPaths.MANIFEST)
        .handler(BodyHandler.create(false).setBodyLimit(maxManifestSize.asLongValue()))
        .blockingHandler(guarded("put manifest", this::putManifest));

    // 6. Tags.
    router.getWithRegex(RegistryPaths.TAGS_LIST).blockingHandler(guarded("list tags", this::listTags));

    // 7. DELETE is deliberately unimplemented — there is no garbage collection, and nothing should
    //    come to depend on deletion semantics before they exist. 405 rather than 404, which would
    //    read as "unknown name" and send a client looking for the wrong problem.
    router
        .route(HttpMethod.DELETE, RegistryPaths.BASE + "/*")
        .handler(
            rc ->
                RegistryErrors.send(
                    rc, 405, OciCode.UNSUPPORTED, "this registry does not implement deletion"));

    // 8. Anything else under /v2 — an OCI-shaped 404, never Vert.x' default HTML page. This is also
    //    the correct answer for /v2/_catalog (enumeration is what the private posture avoids) and
    //    /v2/<name>/referrers/<digest>, neither of which is implemented.
    router.route(RegistryPaths.BASE).handler(this::notFound);
    router.route(RegistryPaths.BASE + "/*").handler(this::notFound);
  }

  /**
   * Stamps {@code Docker-Distribution-Api-Version} ahead of every {@code /v2} route — clients read
   * it to decide they are talking to a v2 registry, so it is emitted from one place, error
   * responses included.
   */
  private static void stampApiVersion(RoutingContext rc) {
    rc.response().putHeader("Docker-Distribution-Api-Version", "registry/2.0");
    rc.next();
  }

  /** {@code GET /v2/} — the version probe, unconditionally 200: anonymous pull needs no login. */
  private void version(RoutingContext rc) {
    rc.response()
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(new JsonObject().encode());
  }

  private void notFound(RoutingContext rc) {
    RegistryErrors.send(
        rc,
        OciCode.UNSUPPORTED,
        "not a route this registry serves",
        Map.of("path", rc.normalizedPath()));
  }

  // --- blobs ------------------------------------------------------------------------------------

  /** {@code GET|HEAD /v2/<name>/blobs/<digest>} — the pull path, and a mirror's fetch path. */
  private void serveBlob(RoutingContext rc, boolean withBody) {
    OciRegistryService.PullTarget target = registry.resolveForPull(rc.pathParam("name"));
    String wireDigest = rc.pathParam("digest");
    String hex = OciDigest.requireHex(wireDigest);

    if (target.mirror()) {
      // The miss path. A no-op on a hit and when no upstream is registered, so the lines below are
      // unchanged for every hosted repository and for every cached byte.
      mirror.ensureBlob(target, hex);
    }

    long size;
    try {
      size = blobStore.size(hex);
    } catch (Exception missing) {
      throw notCached(
          target,
          OciCode.BLOB_UNKNOWN,
          "blob unknown to registry",
          "this mirror has no cached copy of that blob",
          Map.of("digest", wireDigest));
    }

    HttpServerResponse response = rc.response();
    response
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
        .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size))
        .putHeader(DOCKER_CONTENT_DIGEST, wireDigest)
        .putHeader(HttpHeaders.ETAG, "\"" + wireDigest + "\"")
        // Content-addressed: the bytes behind a digest can never mean something else. Same stance
        // as BlobController.serve.
        .putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");

    if (!withBody) {
      // HEAD must carry the same Content-Length as GET — a HEAD reporting 0 makes docker believe it
      // already has the layer and skip one it does not have. BlobSender writes a body
      // unconditionally, so it must not be reached here.
      response.end();
      return;
    }

    // The blob's chunks, written under the client's backpressure on this worker thread — see
    // BlobSender for why the transfer costs a thread now and why it still costs no heap.
    blobSender.send(response, hex, "blob " + hex);
  }

  // --- uploads ----------------------------------------------------------------------------------

  /**
   * {@code POST /v2/<name>/blobs/uploads/} — open a session, mount a blob, or upload monolithically.
   */
  private void startUpload(RoutingContext rc) {
    OciImageName name = registry.requireOciRepository(rc.pathParam("name"));

    String mount = rc.request().getParam("mount");
    if (mount != null) {
      // A cross-repository mount. Dedupe is global and content-addressed, so there is nothing to
      // copy: `from` is ignored entirely because no value of it could change the answer.
      String hex = OciDigest.hexOrNull(mount);
      if (hex != null && blobStore.exists(hex)) {
        created(rc, name, mount);
        return;
      }
      // A MISS must fall through to an ordinary session, exactly as if `mount` had not been sent.
      // Answering 4xx here is the classic way to break `docker push`.
    }

    String digest = rc.request().getParam("digest");
    if (digest != null) {
      // Monolithic: the whole blob arrives with the POST.
      OciUploadSessions.Session session = uploads.open();
      finalizeUpload(rc, name, session, digest);
      return;
    }

    OciUploadSessions.Session session = uploads.open();
    accepted(rc, name, session);
  }

  /** {@code PATCH /v2/<name>/blobs/uploads/<session>} — the streaming upload. */
  private void patchUpload(RoutingContext rc) {
    OciImageName name = registry.requireOciRepository(rc.pathParam("name"));
    OciUploadSessions.Session session = uploads.require(rc.pathParam("session"));
    requireChunkStartsAtTheCurrentOffset(rc, session);

    try (InputStream body = OciRequestBody.open(rc, uploadIdleTimeout.toMillis())) {
      session.append(body, maxLayerSize.asLongValue());
    } catch (java.io.IOException e) {
      uploads.cancel(session);
      throw new OciException(OciCode.BLOB_UPLOAD_INVALID, "upload stream failed: " + e.getMessage());
    } catch (RuntimeException e) {
      uploads.cancel(session);
      throw e;
    }
    accepted(rc, name, session);
  }

  /**
   * {@code PUT /v2/<name>/blobs/uploads/<session>?digest=} — verify and promote.
   *
   * <p>The final chunk is range-checked exactly like a {@code PATCH}: spec §"Pushing a blob in
   * chunks" makes 416 a <b>MUST</b> for an out-of-order final chunk, and this path skipped the check
   * until the upstream conformance suite failed on it. Without it the bytes were appended anyway and
   * the mismatch surfaced as {@code 400 DIGEST_INVALID} — the same rejection, but a code that tells
   * a client its content was wrong rather than that its offset was, so a resumable client retried
   * the upload instead of resyncing. Note the check goes here rather than in
   * {@link #finalizeUpload}: the monolithic {@code POST ...?digest=} shares that method and carries
   * no {@code Content-Range}, and answering 4xx on that path is the classic way to break
   * {@code docker push}.
   */
  private void finishUpload(RoutingContext rc) {
    OciImageName name = registry.requireOciRepository(rc.pathParam("name"));
    OciUploadSessions.Session session = uploads.require(rc.pathParam("session"));
    requireChunkStartsAtTheCurrentOffset(rc, session);
    finalizeUpload(rc, name, session, rc.request().getParam("digest"));
  }

  /** {@code GET /v2/<name>/blobs/uploads/<session>} — how far a resumable upload got. */
  private void uploadStatus(RoutingContext rc) {
    OciImageName name = registry.requireOciRepository(rc.pathParam("name"));
    OciUploadSessions.Session session = uploads.require(rc.pathParam("session"));
    rc.response().setStatusCode(204);
    uploadHeaders(rc, name, session).end();
  }

  /**
   * Reads whatever body this request carries into the session, then verifies and promotes.
   *
   * <p>Shared by the monolithic {@code POST ?digest=} and the {@code PUT} finalize, because they
   * differ only in whether a {@code PATCH} came first. A {@code PUT} after a {@code PATCH} normally
   * has an empty body — reading it is a no-op — but a client that sends its last chunk on the PUT is
   * handled by the same line.
   */
  private void finalizeUpload(
      RoutingContext rc, OciImageName name, OciUploadSessions.Session session, String claimed) {
    if (claimed == null) {
      uploads.cancel(session);
      throw new OciException(
          OciCode.DIGEST_INVALID, "a digest query parameter is required to finalize an upload");
    }
    String expected = OciDigest.requireHex(claimed);

    BlobStore.StagedBlob staged;
    try (InputStream body = OciRequestBody.open(rc, uploadIdleTimeout.toMillis())) {
      session.append(body, maxLayerSize.asLongValue());
      staged = uploads.finish(session);
    } catch (java.io.IOException e) {
      uploads.cancel(session);
      throw new OciException(OciCode.BLOB_UPLOAD_INVALID, "upload stream failed: " + e.getMessage());
    } catch (RuntimeException e) {
      uploads.cancel(session);
      throw e;
    }

    // The digest is free — BlobStore computed it while streaming — and it is never skipped. A blob
    // that does not hash to its name is not a blob.
    if (!staged.sha256().equals(expected)) {
      blobStore.promote(staged); // binds the staged content or dedupes it; wrong bytes are still bytes
      throw new OciException(
          OciCode.DIGEST_INVALID,
          "uploaded content does not match the claimed digest",
          Map.of("expected", claimed, "actual", OciDigest.wire(staged.sha256())));
    }
    blobStore.promote(staged);
    created(rc, name, claimed);
  }

  /**
   * A manifest reference is a tag or a digest, and anything else is a {@code 400} — never a 404.
   *
   * <p>{@link RegistryPaths#REF} matches any non-slash segment so that a malformed reference reaches
   * a handler at all; this is where it is judged. The distinction the two codes carry is the point:
   * a reference carrying {@code :} is an attempt at a digest, so a bad one is {@code DIGEST_INVALID}
   * and the client learns its digest was wrong, while a bad tag is {@code MANIFEST_INVALID}. Both are
   * 400. Answering 404, as this route did until the conformance suite caught it, tells a client the
   * manifest does not exist — which is a different and misleading claim.
   */
  private static void requireWellFormedReference(String reference) {
    if (reference.indexOf(':') >= 0) {
      OciDigest.requireHex(reference); // throws DIGEST_INVALID (400)
      return;
    }
    if (!TAG.matcher(reference).matches()) {
      throw new OciException(
          OciCode.MANIFEST_INVALID,
          "manifest reference is neither a valid tag nor a digest",
          Map.of("reference", reference));
    }
  }

  private static final java.util.regex.Pattern TAG =
      java.util.regex.Pattern.compile(RegistryPaths.TAG);

  /**
   * A {@code Content-Range} whose start is not where the session currently stands is
   * unsatisfiable — answering 416 with the real range is what lets a client resync rather than
   * silently corrupting the blob.
   */
  private static void requireChunkStartsAtTheCurrentOffset(
      RoutingContext rc, OciUploadSessions.Session session) {
    String range = rc.request().getHeader("Content-Range");
    if (range == null) {
      return; // no Content-Range means "append here", which is what docker sends
    }
    int dash = range.indexOf('-');
    if (dash <= 0) {
      throw new OciException(OciCode.BLOB_UPLOAD_INVALID, "malformed Content-Range");
    }
    long start;
    try {
      start = Long.parseLong(range.substring(0, dash).trim());
    } catch (NumberFormatException e) {
      throw new OciException(OciCode.BLOB_UPLOAD_INVALID, "malformed Content-Range");
    }
    if (start != session.written()) {
      rc.response()
          .setStatusCode(416)
          .putHeader("Range", "0-" + Math.max(session.written() - 1, 0))
          .end();
      throw new UploadRangeMismatch();
    }
  }

  /** Signals that a 416 has already been written; nothing further should touch the response. */
  private static final class UploadRangeMismatch extends RuntimeException {
    UploadRangeMismatch() {
      super(null, null, false, false);
    }
  }

  /** {@code 202} with the session's location and current extent. */
  private void accepted(RoutingContext rc, OciImageName name, OciUploadSessions.Session session) {
    rc.response().setStatusCode(202);
    uploadHeaders(rc, name, session).end();
  }

  private HttpServerResponse uploadHeaders(
      RoutingContext rc, OciImageName name, OciUploadSessions.Session session) {
    return rc.response()
        .putHeader(
            HttpHeaders.LOCATION,
            RegistryPaths.BASE + "/" + name.full() + "/blobs/uploads/" + session.id())
        .putHeader(DOCKER_UPLOAD_UUID, session.id().toString())
        .putHeader("Range", "0-" + Math.max(session.written() - 1, 0))
        .putHeader(HttpHeaders.CONTENT_LENGTH, "0");
  }

  /**
   * {@code 201} for a stored blob.
   *
   * <p>{@code Location} is always the absolute-<b>path</b> form, never {@code scheme://host/…}.
   * Behind qits-gateway the host this process sees is not the host the client dialled, and a client
   * resolves a path-form Location against the request URL — which is right by construction.
   */
  private void created(RoutingContext rc, OciImageName name, String wireDigest) {
    rc.response()
        .setStatusCode(201)
        .putHeader(HttpHeaders.LOCATION, RegistryPaths.BASE + "/" + name.full() + "/blobs/" + wireDigest)
        .putHeader(DOCKER_CONTENT_DIGEST, wireDigest)
        .putHeader(HttpHeaders.CONTENT_LENGTH, "0")
        .end();
  }

  // --- manifests --------------------------------------------------------------------------------

  /** {@code GET|HEAD /v2/<name>/manifests/<ref>} — {@code <ref>} is a tag or a digest. */
  private void serveManifest(RoutingContext rc, boolean withBody) {
    OciRegistryService.PullTarget target = registry.resolveForPull(rc.pathParam("name"));
    String reference = rc.pathParam("ref");
    requireWellFormedReference(reference);

    // The one branch a mirror namespace takes here: resolveManifest becomes a resolve-or-fetch,
    // with the TTL, the HEAD revalidation and the serve-stale rule inside it.
    OciRegistryService.StoredManifest manifest =
        (target.mirror()
                ? mirror.resolveManifest(target, reference)
                : registry.resolveManifest(target.name(), reference))
            .orElseThrow(
                () ->
                    notCached(
                        target,
                        OciCode.MANIFEST_UNKNOWN,
                        "manifest unknown to this image",
                        "this mirror has no cached copy of that manifest",
                        Map.of("reference", reference)));
    // Resolve may revalidate a mirror tag and move it. Check the bytes are there, then touch
    // exactly the final row that will be served; probing stale cache state is not itself client
    // access. `size` is the existence gate `locate` used to be, and 404s on the same miss; the
    // length on the wire stays the manifest row's own.
    blobStore.size(manifest.digest());
    registry.touchManifest(target.name(), reference, manifest.digest());

    // Accept is deliberately ignored. We never convert between manifest schemas, so returning what
    // was stored is the only honest answer — 404ing because a client did not list our exact type
    // would read as "image not found", which is a worse lie than an unexpected Content-Type.
    HttpServerResponse response =
        rc.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, manifest.mediaType())
            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(manifest.size()))
            .putHeader(DOCKER_CONTENT_DIGEST, OciDigest.wire(manifest.digest()));

    if (!withBody) {
      response.end();
      return;
    }
    blobSender.send(response, manifest.digest(), "manifest " + manifest.digest());
  }

  /** {@code PUT /v2/<name>/manifests/<ref>} — validate, store, and bind. */
  private void putManifest(RoutingContext rc) {
    OciImageName name = registry.requireOciRepository(rc.pathParam("name"));
    String reference = rc.pathParam("ref");
    requireWellFormedReference(reference);
    byte[] bytes = rc.body().buffer() == null ? new byte[0] : rc.body().buffer().getBytes();

    OciManifestParser.ParsedManifest parsed =
        manifestParser.parse(bytes, rc.request().getHeader(HttpHeaders.CONTENT_TYPE));

    // Hash the bytes exactly as received. A manifest's digest covers its literal whitespace, so
    // re-serializing would produce a document nobody can address.
    BlobStore.StagedBlob staged =
        blobStore.stage(new java.io.ByteArrayInputStream(bytes), maxManifestSize.asLongValue());
    String digest = staged.sha256();

    // If the reference IS a digest it must be this one. This is the manifest half of "digest
    // verification is non-negotiable" — the half it is tempting to skip because the bytes are small.
    if (OciDigest.isDigest(reference) && !reference.equals(OciDigest.wire(digest))) {
      blobStore.promote(staged);
      throw new OciException(
          OciCode.DIGEST_INVALID,
          "manifest does not hash to the digest it was PUT under",
          Map.of("expected", reference, "actual", OciDigest.wire(digest)));
    }

    // Everything the manifest names must already be here, checked BEFORE anything is bound so a
    // truncated push leaves no half-resolvable tag behind.
    registry.requireReferencesExist(name, parsed.index(), parsed.references());

    blobStore.promote(staged);
    registry.bindManifest(name, reference, digest, parsed.mediaType(), bytes.length);

    rc.response()
        .setStatusCode(201)
        .putHeader(
            HttpHeaders.LOCATION,
            RegistryPaths.BASE + "/" + name.full() + "/manifests/" + OciDigest.wire(digest))
        .putHeader(DOCKER_CONTENT_DIGEST, OciDigest.wire(digest))
        .putHeader(HttpHeaders.CONTENT_LENGTH, "0")
        .end();
  }

  // --- tags -------------------------------------------------------------------------------------

  /** {@code GET /v2/<name>/tags/list}, with the spec's {@code ?n=} and {@code ?last=} paging. */
  private void listTags(RoutingContext rc) {
    OciImageName name = registry.resolveForPull(rc.pathParam("name")).name();
    int limit = pageSize(rc.request().getParam("n"));
    String last = rc.request().getParam("last");

    List<String> tags = registry.listTags(name, last, limit);
    JsonObject body =
        new JsonObject().put("name", name.full()).put("tags", new JsonArray(tags));

    HttpServerResponse response =
        rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
    if (tags.size() == limit) {
      // A full page may not be the last one. The spec's continuation is a Link header the client
      // follows verbatim, so the cursor is the tag we stopped at rather than an offset.
      response.putHeader(
          "Link",
          "<"
              + RegistryPaths.BASE
              + "/"
              + name.full()
              + "/tags/list?n="
              + limit
              + "&last="
              + tags.get(tags.size() - 1)
              + ">; rel=\"next\"");
    }
    response.end(body.encode());
  }

  private static int pageSize(String n) {
    if (n == null) {
      return 1000;
    }
    try {
      return Math.clamp(Integer.parseInt(n), 1, 1000);
    } catch (NumberFormatException e) {
      throw new OciException(OciCode.UNSUPPORTED, "n must be a number");
    }
  }

  // --- plumbing ---------------------------------------------------------------------------------

  /**
   * The 404 a miss gets, phrased for the namespace it missed in.
   *
   * <p>In a hosted repository the answer is unchanged: the image does not have that manifest or that
   * blob. In a <b>mirror</b> namespace it now means something much narrower than it did, because
   * the miss path answers everything else itself: an upstream that has no such reference throws its
   * own 404 naming the registry it asked, and an unreachable one throws a 502. What is left here is
   * the namespace whose <b>upstream row was deleted</b> while its cache stayed — the append-only
   * posture, where what is cached keeps serving and what is not can no longer be fetched. Saying
   * that in so many words is what keeps a puller from reading the 404 as a broken mirror.
   */
  private static OciException notCached(
      OciRegistryService.PullTarget target,
      OciCode code,
      String hostedMessage,
      String mirrorMessage,
      Map<String, Object> detail) {
    if (!target.mirror()) {
      return new OciException(code, hostedMessage, detail);
    }
    Map<String, Object> mirrorDetail = new java.util.HashMap<>(detail);
    mirrorDetail.put("namespace", target.name().repository());
    if (target.upstreamDomain() != null) {
      mirrorDetail.put("upstream", target.upstreamDomain());
    }
    return new OciException(
        code,
        mirrorMessage
            + (target.upstreamDomain() == null
                ? "; no upstream is registered for this namespace, so nothing can be fetched into it"
                : ", and " + target.upstreamDomain() + " did not supply one"),
        mirrorDetail);
  }

  /**
   * Wraps a handler so every throwable becomes the spec's error envelope rather than
   * {@code QuarkusErrorHandler}'s.
   */
  private Handler<RoutingContext> guarded(String what, Handler<RoutingContext> handler) {
    return rc -> {
      try {
        handler.handle(rc);
      } catch (UploadRangeMismatch alreadyAnswered) {
        // The 416 and its Range header are already on the wire.
      } catch (Throwable thrown) {
        RegistryErrors.fail(rc, what, thrown);
      }
    };
  }
}
