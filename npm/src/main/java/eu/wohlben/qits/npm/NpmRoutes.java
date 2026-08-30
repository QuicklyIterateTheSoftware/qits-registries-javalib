package eu.wohlben.qits.npm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.NpmIntegrity;
import eu.wohlben.qits.artifacts.control.NpmPackageName;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import eu.wohlben.qits.artifacts.control.NpmRegistryService;
import eu.wohlben.qits.artifacts.error.NpmException;
import eu.wohlben.qits.registry.BlobSender;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The npm registry API, hosted and proxied, at {@code /artifacts/npm/<repository>/…}.
 *
 * <p>Unlike {@code /v2} the mount point <em>is</em> a choice, and this is the ordinary one: npm
 * accepts a registry URL of any depth, so there is no reason to claim a second root-level segment
 * and every reason not to — this sits inside the {@code /artifacts} prefix the gateway already
 * routes here. The segment is still a literal in the code, exactly like {@code GitHostRoutes.BASE}:
 * no config key moves it and no JAX-RS test would notice if it drifted.
 *
 * <p>The first path segment after the base is the {@code artifact_repository} row, the same
 * first-segment rule the OCI registry uses. Two are seeded — {@code npm} (hosted) and {@code npmjs}
 * (a pull-through cache of npmjs) — which is what lets one {@code .npmrc} route everything:
 *
 * <pre>
 *   registry=&lt;…/artifacts/npm/npmjs/&gt;
 *   @qits:registry=&lt;…/artifacts/npm/npm/&gt;
 * </pre>
 *
 * <p><b>There is no authentication here, at all</b> — not a token, not a guard, nothing. That is the
 * OCI registry's threat model verbatim (README, "No login, in either direction"): on qits-net
 * producers and consumers are trusted, and from outside {@code /artifacts/npm/**} falls under
 * qits-gateway's ordinary session auth like any other non-allowlisted artifacts path. The one
 * client-side wrinkle is npm's own {@code ENEEDAUTH} pre-flight, which never reaches the wire; a
 * dummy {@code _authToken} line in a pipeline's {@code .npmrc} is CLI ceremony that this server
 * neither reads nor knows about.
 *
 * <p>No handler here calls {@code rc.fail()} and no handler returns a DTO — see {@code NpmErrors}
 * and {@code NpmPackuments} for both reasons, which are the registry's, unchanged.
 */
@ApplicationScoped
public class NpmRoutes {

  private static final Logger LOG = Logger.getLogger(NpmRoutes.class);

  /** What a forwarded authority may look like: host, optional port, or a bracketed IPv6 literal. */
  private static final Pattern AUTHORITY =
      Pattern.compile("[A-Za-z0-9.\\-\\[\\]:]{1,253}(?::\\d{1,5})?");

  /** The two schemes a tarball URL can carry; anything else is a malformed forwarding header. */
  private static final Pattern SCHEME = Pattern.compile("https?");

  @Inject NpmRegistryService registry;
  @Inject NpmUpstream upstream;
  @Inject BlobStore blobStore;
  @Inject BlobSender blobSender;
  @Inject ObjectMapper json;

  @ConfigProperty(name = "qits.artifacts.npm.max-publish-size", defaultValue = "32M")
  MemorySize maxPublishSize;

  void init(@Observes Router router) {
    // 1. Tarballs first — the more specific of the two shapes. They cannot actually collide (a
    //    package name is at most two components and neither may contain a slash, so nothing under
    //    /-/ can be read as one), but ordering them by specificity means a future loosening of the
    //    name grammar fails a test rather than silently answering with the wrong handler.
    //
    //    HEAD is NOT derived from GET by Vert.x. It needs its own route or every client that probes
    //    before downloading sees a 404.
    router
        .headWithRegex(NpmPaths.TARBALL)
        .blockingHandler(guarded("head tarball", rc -> serveTarball(rc, false)));
    router
        .getWithRegex(NpmPaths.TARBALL)
        .blockingHandler(guarded("get tarball", rc -> serveTarball(rc, true)));

    // 2. Packuments.
    router
        .headWithRegex(NpmPaths.PACKUMENT)
        .blockingHandler(guarded("head packument", rc -> servePackument(rc, false)));
    router
        .getWithRegex(NpmPaths.PACKUMENT)
        .blockingHandler(guarded("get packument", rc -> servePackument(rc, true)));

    // 3. Publish. The one route that buffers, and the reason it needs a STATED limit: BodyHandler
    //    defaults to 10 MiB (vertx-web's own default, the bug the git host's max-pack-size exists
    //    for), and the publish document carries the tarball base64-inflated by 4/3 inside JSON.
    //    Sized well under quarkus.http.limits.max-body-size so the application's 413 wins the race
    //    and the client gets a message rather than a reset connection.
    router
        .putWithRegex(NpmPaths.PACKUMENT)
        .handler(BodyHandler.create(false).setBodyLimit(maxPublishSize.asLongValue()))
        .blockingHandler(guarded("publish", this::publish));

    // 4. DELETE is deliberately unimplemented, exactly as on /v2: there is no garbage collection and
    //    nothing should come to depend on unpublish semantics before they exist. 405 rather than
    //    404, which would read as "unknown package" and send a client looking for the wrong problem.
    router
        .route(HttpMethod.DELETE, NpmPaths.BASE + "/*")
        .handler(
            rc ->
                NpmErrors.send(
                    rc, 405, "this registry does not implement unpublish"));

    // 5. Everything else under the base — /-/v1/search, /-/npm/v1/security/audits, /-/whoami, the
    //    login handshake — is a JSON 404, never Vert.x' default HTML page. npm degrades gracefully
    //    on all of them: a search 404s to "no results", an audit 404s to "not audited", and an
    //    install proceeds. That is why they are absent rather than stubbed.
    router.route(NpmPaths.BASE).handler(this::notFound);
    router.route(NpmPaths.BASE + "/*").handler(this::notFound);
  }

  private void notFound(RoutingContext rc) {
    NpmErrors.send(rc, 404, "not a route this npm registry serves: " + rc.normalizedPath());
  }

  // --- packuments -------------------------------------------------------------------------------

  /**
   * {@code GET|HEAD /artifacts/npm/<repo>/<pkg>} — the document npm resolves every install against.
   *
   * <p>{@code Accept: application/vnd.npm.install-v1+json} — the abbreviated form both npm and pnpm
   * send — is answered with the <b>full</b> document. That is spec-legal (the abbreviated type is an
   * optimization a registry may decline) and it is the honest first implementation: trimming it is a
   * bandwidth change, not a correctness one, and doing it wrong silently breaks installs that need a
   * field we dropped.
   */
  private void servePackument(RoutingContext rc, boolean withBody) {
    String repository = rc.pathParam("repository");
    String type = registry.requireNpmRepository(repository);
    NpmPackageName pkg = packageOf(rc);
    String tarballBase = externalBase(rc, repository);

    ObjectNode document;
    if (NpmProxyProfile.KEY.equals(type)) {
      document = NpmPackuments.rewritten(upstream.packument(repository, pkg), pkg, tarballBase);
    } else {
      List<NpmRegistryService.StoredVersion> versions =
          registry.listVersions(repository, pkg.full());
      if (versions.isEmpty()) {
        // The npm CLI reads this as "not published yet" and proceeds, which is exactly what a first
        // publish needs it to do.
        throw new NpmException(404, "no such package: " + pkg.full());
      }
      document =
          NpmPackuments.hosted(
              json, pkg, versions, registry.distTags(repository, pkg.full()), tarballBase);
    }

    byte[] body = document.toString().getBytes(StandardCharsets.UTF_8);
    HttpServerResponse response =
        rc.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
            .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length));
    if (!withBody) {
      response.end();
      return;
    }
    response.end(Buffer.buffer(body));
  }

  // --- tarballs ---------------------------------------------------------------------------------

  /**
   * {@code GET|HEAD /artifacts/npm/<repo>/<pkg>/-/<file>.tgz} — <b>one</b> code path for both types.
   *
   * <p>The only difference the proxy makes is what happens on a miss: a hosted repository has a 404
   * to give, and a proxy has an upstream to ask. Everything after that — the row, the blob, the
   * headers, the send — is identical, which is the whole reason proxied versions get {@code
   * npm_version} rows written lazily on their first pull.
   */
  private void serveTarball(RoutingContext rc, boolean withBody) {
    String repository = rc.pathParam("repository");
    String type = registry.requireNpmRepository(repository);
    NpmPackageName pkg = packageOf(rc);
    String file = rc.pathParam("file");

    String version = pkg.versionOfTarball(file);
    if (version == null) {
      throw new NpmException(404, file + " is not a tarball of " + pkg.full());
    }

    NpmRegistryService.StoredVersion stored =
        registry
            .findVersion(repository, pkg.full(), version)
            .orElseGet(
                () -> {
                  if (!NpmProxyProfile.KEY.equals(type)) {
                    throw new NpmException(404, "no such version: " + pkg.full() + "@" + version);
                  }
                  return upstream.fetchTarball(repository, pkg, version);
                });

    long size;
    try {
      size = blobStore.size(stored.tarballBlobId());
    } catch (Exception missing) {
      throw new NpmException(404, "the tarball of " + pkg.full() + "@" + version + " is not stored");
    }

    // Size first, then touch — a row whose bytes are gone is a 404, not an access. One call for
    // both types, because a proxied version is an ordinary npm_version row: the pull that created it
    // counts as its first access, and the packument's fetched_at is left alone. HEAD counts too,
    // which is the stance the OCI manifest route already takes.
    registry.touchVersion(repository, pkg.full(), version);

    HttpServerResponse response =
        rc.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size))
            .putHeader(HttpHeaders.ETAG, "\"" + stored.tarballBlobId() + "\"")
            // A published version is immutable and a tarball is content-addressed underneath, so
            // the bytes behind this URL can never mean something else. Same stance as the OCI blob
            // route and BlobController.serve.
            .putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
    if (stored.integrity() != null) {
      // Not a header npm reads — it verifies against the packument — but it makes a curl'd tarball
      // checkable without one.
      response.putHeader("X-Npm-Integrity", stored.integrity());
    }

    if (!withBody) {
      // HEAD must carry the same Content-Length as GET, and BlobSender writes a body
      // unconditionally, so it must not be reached here.
      response.end();
      return;
    }

    // The tarball's chunks, written under the client's backpressure on this worker thread — see
    // BlobSender for why the transfer costs a thread now and why it still costs no heap.
    blobSender.send(
        response, stored.tarballBlobId(), "npm tarball " + pkg.full() + "@" + version);
  }

  // --- publish ----------------------------------------------------------------------------------

  /**
   * {@code PUT /artifacts/npm/<repo>/<pkg>} — the publish document.
   *
   * <p>npm sends one JSON object carrying the version's manifest, the dist-tags to move, and the
   * tarball itself base64-encoded under {@code _attachments}. Both hashes are <b>recomputed</b> from
   * the decoded bytes and compared with what the client claimed: a tarball that does not hash to its
   * own {@code integrity} is not that tarball, which is the npm restatement of the registry's "a
   * blob that does not hash to its name is not a blob".
   *
   * <p>The bytes are then staged through {@code BlobStore} like everything else, so the tarball's
   * <em>storage</em> key is its sha256 while npm's sha1/sha512 live in columns and are re-emitted in
   * packuments. The store stays sha256-only.
   */
  private void publish(RoutingContext rc) {
    String repository = rc.pathParam("repository");
    String type = registry.requireNpmRepository(repository);
    if (!NpmPackagesProfile.KEY.equals(type)) {
      // Refused by TYPE, not by configuration: a mirror that accepted a publish would let cached
      // upstream content and published content share a namespace, which is the one thing the
      // two-type split exists to prevent.
      throw new NpmException(
          405,
          "'"
              + repository
              + "' is a pull-through cache of an upstream registry and accepts no publishes; "
              + "publish to a npm-packages repository instead");
    }
    NpmPackageName pkg = packageOf(rc);

    JsonNode document = body(rc);
    String declared = document.path("name").asText(null);
    if (declared != null && !declared.isBlank() && !declared.equals(pkg.full())) {
      throw new NpmException(
          400, "the document names " + declared + " but was PUT under " + pkg.full());
    }
    JsonNode versions = document.path("versions");
    if (!versions.isObject() || versions.isEmpty()) {
      throw new NpmException(400, "the publish document carries no versions");
    }
    JsonNode attachments = document.path("_attachments");

    for (Map.Entry<String, JsonNode> entry : versions.properties()) {
      publishOne(repository, pkg, entry.getKey(), entry.getValue(), attachments, document);
    }

    rc.response()
        .setStatusCode(201)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(new JsonObject().put("ok", true).put("id", pkg.full()).encode());
  }

  private void publishOne(
      String repository,
      NpmPackageName pkg,
      String version,
      JsonNode manifest,
      JsonNode attachments,
      JsonNode document) {

    if (!manifest.isObject()) {
      throw new NpmException(400, "the manifest for " + version + " is not a JSON object");
    }
    byte[] tarball = attachmentBytes(attachments, pkg, version);

    String shasum = NpmIntegrity.shasum(tarball);
    String integrity = NpmIntegrity.integrity(tarball);
    JsonNode dist = manifest.path("dist");
    requireClaimMatches(dist.path("shasum").asText(null), shasum, "shasum", pkg, version);
    requireClaimMatches(dist.path("integrity").asText(null), integrity, "integrity", pkg, version);

    // Single request, whole body already buffered — no incremental session is needed here, unlike
    // the OCI upload, whose PATCH and PUT are separate requests.
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(tarball), maxPublishSize.asLongValue());
    blobStore.promote(staged);

    registry.publish(
        repository,
        pkg.full(),
        version,
        staged.sha256(),
        integrity,
        shasum,
        manifest.toString(),
        tagsNaming(document, version));
  }

  /**
   * The dist-tags this publish moves onto {@code version}.
   *
   * <p>Defaults to {@code latest} when the document carries none, which is what {@code npm publish}
   * means by a bare publish — a package whose {@code dist-tags} is empty is one npm cannot resolve
   * a bare {@code npm install <name>} against.
   */
  private static Map<String, String> tagsNaming(JsonNode document, String version) {
    Map<String, String> tags = new LinkedHashMap<>();
    JsonNode declared = document.path("dist-tags");
    if (declared.isObject()) {
      for (Map.Entry<String, JsonNode> entry : declared.properties()) {
        if (version.equals(entry.getValue().asText(null))) {
          tags.put(entry.getKey(), version);
        }
      }
    }
    if (tags.isEmpty()) {
      tags.put("latest", version);
    }
    return tags;
  }

  /**
   * The tarball for one version, out of {@code _attachments}.
   *
   * <p>npm keys an attachment by {@code <full name>-<version>.tgz} — the <b>scoped</b> name, so a
   * scoped package's key contains a slash — while the URL it is served from uses the unscoped one.
   * Both spellings are looked for, and a single-attachment document (which is what every real
   * publish is) falls back to whatever is in it, because getting stuck on a key format would be a
   * silly way to reject a valid publish.
   */
  private static byte[] attachmentBytes(JsonNode attachments, NpmPackageName pkg, String version) {
    if (!attachments.isObject() || attachments.isEmpty()) {
      throw new NpmException(400, "the publish document carries no _attachments");
    }
    JsonNode attachment = attachments.path(pkg.full() + "-" + version + ".tgz");
    if (!attachment.isObject()) {
      attachment = attachments.path(pkg.tarballFile(version));
    }
    if (!attachment.isObject() && attachments.size() == 1) {
      attachment = attachments.properties().iterator().next().getValue();
    }
    if (!attachment.isObject()) {
      throw new NpmException(400, "no attachment for version " + version);
    }
    String data = attachment.path("data").asText(null);
    if (data == null || data.isBlank()) {
      throw new NpmException(400, "the attachment for version " + version + " carries no data");
    }
    try {
      // The MIME decoder, not the basic one: it tolerates the line breaks some clients insert and
      // accepts an unbroken string just the same.
      return Base64.getMimeDecoder().decode(data);
    } catch (IllegalArgumentException notBase64) {
      throw new NpmException(400, "the attachment for version " + version + " is not base64");
    }
  }

  private static void requireClaimMatches(
      String claimed, String computed, String what, NpmPackageName pkg, String version) {
    if (claimed != null && !claimed.isBlank() && !claimed.equals(computed)) {
      throw new NpmException(
          400,
          "the tarball of "
              + pkg.full()
              + "@"
              + version
              + " does not match its claimed "
              + what
              + " (claimed "
              + claimed
              + ", computed "
              + computed
              + ")");
    }
  }

  // --- plumbing ---------------------------------------------------------------------------------

  private NpmPackageName packageOf(RoutingContext rc) {
    return NpmPackageName.parse(NpmPackageName.decodePathSegment(rc.pathParam("pkg")));
  }

  private JsonNode body(RoutingContext rc) {
    Buffer buffer = rc.body() == null ? null : rc.body().buffer();
    if (buffer == null || buffer.length() == 0) {
      throw new NpmException(400, "the publish request carried no body");
    }
    try {
      JsonNode parsed = json.readTree(buffer.getBytes());
      if (parsed == null || !parsed.isObject()) {
        throw new NpmException(400, "the publish document is not a JSON object");
      }
      return parsed;
    } catch (NpmException already) {
      throw already;
    } catch (Exception unparseable) {
      throw new NpmException(400, "the publish document is not valid JSON");
    }
  }

  /**
   * The absolute base every {@code dist.tarball} is built from.
   *
   * <p>npm refuses a relative tarball URL, so the OCI registry's path-form {@code Location} trick
   * does not transfer and this service has to name a host. It is <b>not</b> a config key: the
   * gateway emits the {@code X-Forwarded-*} set on every proxied request by default, and a client
   * dialling {@code qits-artifacts:8080} on qits-net has no forwarding hop — so the request itself
   * always carries the right answer, while a configured value would be right for one caller and
   * quietly wrong for the other.
   *
   * <p>The forwarded authority is shape-checked before it is used. Not because the threat model
   * needs it — inside the deployment everything here is trusted — but because a malformed value
   * produces a document whose tarball URLs fail far away from here, and a 400 at the boundary is a
   * much shorter path to the cause.
   */
  private static String externalBase(RoutingContext rc, String repository) {
    HttpServerRequest request = rc.request();
    String forwardedHost = firstToken(request.getHeader("X-Forwarded-Host"));
    String authority;
    String scheme;
    if (forwardedHost != null) {
      authority = forwardedHost;
      String forwardedProto = firstToken(request.getHeader("X-Forwarded-Proto"));
      scheme =
          forwardedProto == null
              ? defaultScheme(request)
              : forwardedProto.toLowerCase(Locale.ROOT);
      // The gateway splits the dialled authority across two headers: X-Forwarded-Host carries the
      // host alone and the port travels separately. Reading only the host silently rewrites
      // localhost:8080 into localhost — a document whose tarball urls dial port 80.
      String forwardedPort = firstToken(request.getHeader("X-Forwarded-Port"));
      if (forwardedPort != null
          && authority.indexOf(':') < 0
          && !isDefaultPort(scheme, forwardedPort)) {
        authority = authority + ":" + forwardedPort;
      }
    } else {
      HostAndPort dialled = request.authority();
      authority = dialled == null ? null : dialled.toString();
      scheme = defaultScheme(request);
    }
    if (authority == null
        || !AUTHORITY.matcher(authority).matches()
        || !SCHEME.matcher(scheme).matches()) {
      throw new NpmException(
          400, "cannot build an absolute tarball url from this request's Host/X-Forwarded-* headers");
    }
    return scheme + "://" + authority + NpmPaths.BASE + "/" + repository;
  }

  private static String defaultScheme(HttpServerRequest request) {
    return request.isSSL() ? "https" : "http";
  }

  /** A default port re-appended would be harmless but ugly; canonical urls omit it. */
  private static boolean isDefaultPort(String scheme, String port) {
    return ("http".equals(scheme) && "80".equals(port))
        || ("https".equals(scheme) && "443".equals(port));
  }

  /** {@code X-Forwarded-*} may be a comma-joined chain; the first entry is the original client's. */
  private static String firstToken(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    int comma = header.indexOf(',');
    String first = (comma < 0 ? header : header.substring(0, comma)).trim();
    return first.isEmpty() ? null : first;
  }

  /**
   * Wraps a handler so every throwable becomes npm's {@code {"error": …}} rather than
   * {@code QuarkusErrorHandler}'s HTML.
   */
  private Handler<RoutingContext> guarded(String what, Handler<RoutingContext> handler) {
    return rc -> {
      try {
        handler.handle(rc);
      } catch (Throwable thrown) {
        NpmErrors.fail(rc, what, thrown);
      }
    };
  }
}
