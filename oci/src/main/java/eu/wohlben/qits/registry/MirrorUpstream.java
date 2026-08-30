package eu.wohlben.qits.registry;

import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.OciDigest;
import eu.wohlben.qits.artifacts.control.OciImageName;
import eu.wohlben.qits.artifacts.control.OciManifestParser;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.control.OciRegistryService;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@code OCI_MIRROR} miss path: the only place this registry's <b>serving</b> path leaves the
 * deployment.
 *
 * <p>One flow, all upstreams, table-driven. A {@code GET} that resolves into a mirror namespace and
 * finds nothing cached fetches from the registry the {@code oci_mirror_upstream} row names, verifies
 * the digest while the bytes stream, promotes through the same funnel a push uses, binds the
 * ordinary {@code oci_manifest}/{@code oci_tag} rows and serves. A hit never reaches this class at
 * all — that is the whole design, and it is why {@code qits/*}, npm and git gained no new failure
 * mode from any of it.
 *
 * <p><b>Two cache semantics, decided by what can move:</b>
 *
 * <ul>
 *   <li>Anything addressed <b>by digest</b> — manifests-by-digest and every blob — is immutable and
 *       is cached <b>forever</b>, revalidated never. The digest is the whole contract; bytes that
 *       hash to it cannot mean something else tomorrow.
 *   <li>A <b>tag</b> is a movable pointer, and {@code jdk-25} and {@code 9.6} genuinely move under
 *       toolchain and security updates. So it carries a TTL, and on expiry it is <b>revalidated</b>
 *       by {@code HEAD} rather than refetched: a registry {@code HEAD} returns {@code
 *       Docker-Content-Digest}, and Docker Hub does not count one against its pull limit, so an
 *       unchanged tag costs nothing at all. {@code oci_mirror_tag_check} is where that state lives.
 * </ul>
 *
 * <p><b>Children are fetched lazily, and that ordering is deliberate.</b> A pulled index is bound
 * the moment it arrives, with no child present — the push path's {@code requireReferencesExist} is
 * not applied here, because pull order is the reverse of push order. Each child then arrives as its
 * own miss when a client asks for it by digest, so an architecture nobody pulls is never paid for.
 * A multi-arch pull counts once per architecture <em>fetched</em> upstream, which makes lazy the
 * rate-limit-correct order as well as the cheap one.
 *
 * <p><b>Offline, this cache is strictly additive.</b> Digest-addressed content serves forever with
 * no upstream contact; a stale tag serves stale when the upstream cannot be reached, so once a base
 * image has been pulled once every later build succeeds with the internet down. Only a never-cached
 * reference can fail, and it fails as a {@code 502} that says the upstream is unreachable — never a
 * {@code 500}, and never a silent success. A fresh platform's first build needed the internet
 * before this existed and still does.
 *
 * <p><b>Every wait is bounded.</b> This is the platform's first hard runtime dependency on the
 * public internet inside a request, and after the {@code FROM} rewrite it sits under every service
 * build, so a hung upstream must never pin a worker thread indefinitely. The posture is {@code
 * NpmUpstream}'s, inherited wholesale: a JDK {@link HttpClient} <b>instance</b> field (never
 * static — the native-image rule), a connect timeout, a per-request timeout sized by what is being
 * fetched, and no retries.
 */
@ApplicationScoped
public class MirrorUpstream {

  private static final Logger LOG = Logger.getLogger(MirrorUpstream.class);

  private static final String DOCKER_CONTENT_DIGEST = "Docker-Content-Digest";

  /** Every manifest type this registry stores, so an upstream never converts on our behalf. */
  private static final String MANIFEST_ACCEPT = String.join(", ", OciMediaTypes.allManifestTypes());

  /**
   * An <b>instance</b> field, not a static one — the fourth outbound client in this process and the
   * fourth time the rule holds. A static {@link HttpClient} is built by the class initialiser, which
   * under GraalVM runs at image-build time, and native-image then refuses the image over an {@code
   * HttpClientFacade} in the heap. {@code @ApplicationScoped} still means one client per process.
   */
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  private final MirrorBearerTokens tokens = new MirrorBearerTokens();

  @Inject OciRegistryService registry;
  @Inject OciManifestParser manifestParser;
  @Inject BlobStore blobStore;

  @ConfigProperty(name = "qits.artifacts.oci.mirror.tag-ttl", defaultValue = "PT1H")
  Duration tagTtl;

  @ConfigProperty(name = "qits.artifacts.oci.mirror.manifest-timeout", defaultValue = "PT30S")
  Duration manifestTimeout;

  @ConfigProperty(name = "qits.artifacts.oci.mirror.blob-timeout", defaultValue = "PT10M")
  Duration blobTimeout;

  /**
   * An {@code Optional}, not a {@code String} with an empty default, and the difference is not
   * stylistic: SmallRye reads a <b>configured-empty</b> value as <em>absent</em>, so the blank line
   * this key ships as makes a {@code String} injection fail with "Failed to load config value of
   * type java.lang.String" — at <b>boot</b>, in the packaged binary only, because every test
   * configures a real value. The same rule {@code qits.repositories.git.push-token} carries, and
   * {@code PackagedProcessIT} is what caught it.
   */
  @ConfigProperty(name = "qits.artifacts.oci.mirror.endpoint-override")
  Optional<String> endpointOverride;

  @ConfigProperty(name = "qits.artifacts.oci.max-layer-size", defaultValue = "1G")
  MemorySize maxLayerSize;

  @ConfigProperty(name = "qits.artifacts.oci.max-manifest-size", defaultValue = "4M")
  MemorySize maxManifestSize;

  /** An upstream that could not be reached, as opposed to one that answered something. */
  private static final class Unreachable extends RuntimeException {
    Unreachable(Throwable cause) {
      super(cause == null ? null : cause.toString(), cause, false, false);
    }
  }

  // --- manifests --------------------------------------------------------------------------------

  /**
   * The manifest to serve for a reference in a mirror namespace, fetching it if this registry has
   * no copy.
   *
   * <p>Returns empty only when nothing can be done: no upstream is registered for the namespace and
   * nothing is cached. Every other failure throws, because a caller cannot tell an empty "not here"
   * from an empty "upstream said no" and the difference is the whole of what a puller needs to read.
   */
  public Optional<OciRegistryService.StoredManifest> resolveManifest(
      OciRegistryService.PullTarget target, String reference) {

    Optional<OciRegistryService.StoredManifest> cached =
        registry.resolveManifest(target.name(), reference);
    if (target.upstreamDomain() == null) {
      // The upstream row was deleted while its cache stayed (the append-only posture). What is here
      // still serves; what is not can no longer be fetched, and the route says so.
      return cached;
    }
    if (OciDigest.isDigest(reference)) {
      // Immutable: a hit is final, and a miss is one fetch that can never need repeating.
      if (cached.isPresent()) {
        return cached;
      }
      try {
        return Optional.of(fetchManifest(target, reference, OciDigest.hexOrNull(reference)));
      } catch (Unreachable unreachable) {
        throw upstreamUnreachable(target, "manifest", reference);
      }
    }
    return Optional.of(tag(target, reference, cached));
  }

  /**
   * The three-way tag path: fresh, revalidate, or cold.
   *
   * <p>The order matters more than it looks. A fresh row is answered with <b>zero</b> upstream
   * traffic, which is what makes the cache worth having under a build that pulls the same base
   * image on every step. A stale row costs one {@code HEAD}, and only a moved digest costs a
   * transfer.
   */
  private OciRegistryService.StoredManifest tag(
      OciRegistryService.PullTarget target,
      String tag,
      Optional<OciRegistryService.StoredManifest> cached) {

    if (cached.isPresent() && fresh(target.name(), tag)) {
      return cached.get();
    }
    if (cached.isEmpty()) {
      try {
        return fetchManifest(target, tag, null);
      } catch (Unreachable unreachable) {
        throw upstreamUnreachable(target, "manifest", tag);
      }
    }

    String upstreamDigest;
    try {
      upstreamDigest = headDigest(target, tag);
    } catch (Unreachable unreachable) {
      // The serve-stale rule, and half of why this cache exists: a build that has pulled this base
      // image once keeps building through an upstream outage.
      LOG.infof(
          "mirror: serving %s:%s stale — %s is unreachable",
          target.name().full(), tag, target.upstreamDomain());
      return cached.get();
    }

    if (upstreamDigest == null) {
      // Upstream answered, but no longer has the tag, or would not say what it points at. The
      // cached copy is what this store has and what it keeps: nothing here deletes.
      LOG.debugf(
          "mirror: %s no longer resolves %s:%s — serving the cached copy",
          target.upstreamDomain(), target.name().full(), tag);
      return cached.get();
    }
    if (upstreamDigest.equals(cached.get().digest())) {
      registry.recordMirrorTagCheck(target.name(), tag, Instant.now());
      return cached.get();
    }
    try {
      return fetchManifest(target, tag, upstreamDigest);
    } catch (Unreachable unreachable) {
      LOG.infof(
          "mirror: %s:%s moved upstream but %s went away mid-fetch — serving the cached copy",
          target.name().full(), tag, target.upstreamDomain());
      return cached.get();
    }
  }

  /** Whether the tag was agreed with its upstream recently enough to serve without asking. */
  private boolean fresh(OciImageName name, String tag) {
    return registry
        .mirrorTagCheckedAt(name, tag)
        .filter(checkedAt -> checkedAt.isAfter(Instant.now().minus(tagTtl)))
        .isPresent();
  }

  /**
   * The upstream's current digest for a tag, in bare hex — one {@code HEAD}, which is free on Docker
   * Hub and cheap everywhere else.
   *
   * @return null if the upstream does not have the tag, or answered without a digest header
   */
  private String headDigest(OciRegistryService.PullTarget target, String tag) {
    HttpResponse<Void> response =
        sendAuthenticated(
            target,
            manifestUri(target, tag),
            manifestTimeout,
            builder -> builder.header("Accept", MANIFEST_ACCEPT).method("HEAD", noBody()),
            HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() != 200) {
      return null;
    }
    return OciDigest.hexOrNull(response.headers().firstValue(DOCKER_CONTENT_DIGEST).orElse(null));
  }

  /**
   * Fetches one manifest, verifies it, promotes it and binds it.
   *
   * @param expectedHex the digest the bytes must hash to, or null when a tag was asked for and the
   *     upstream has not been asked what it points at
   */
  private OciRegistryService.StoredManifest fetchManifest(
      OciRegistryService.PullTarget target, String reference, String expectedHex) {

    HttpResponse<InputStream> response =
        sendAuthenticated(
            target,
            manifestUri(target, reference),
            manifestTimeout,
            builder -> builder.header("Accept", MANIFEST_ACCEPT).GET(),
            HttpResponse.BodyHandlers.ofInputStream());

    if (response.statusCode() == 404) {
      drain(response.body());
      throw new OciException(
          OciCode.MANIFEST_UNKNOWN,
          "this mirror has no cached copy, and "
              + target.upstreamDomain()
              + " has no such manifest either",
          Map.of(
              "reference", reference,
              "namespace", target.name().repository(),
              "image", target.name().image(),
              "upstream", target.upstreamDomain()));
    }
    if (response.statusCode() != 200) {
      drain(response.body());
      throw upstreamAnswered(target, response.statusCode(), reference);
    }

    byte[] bytes;
    try (InputStream body = response.body()) {
      bytes = readCapped(body, maxManifestSize.asLongValue());
    } catch (IOException truncated) {
      throw new Unreachable(truncated);
    }

    // Staged first, because staging is what hashes: the store computes a digest while it streams,
    // so there is no second implementation of "what does this hash to" anywhere in this service.
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(bytes), maxManifestSize.asLongValue());
    String hex = staged.sha256();

    OciManifestParser.ParsedManifest parsed;
    try {
      // The digest is the contract, and both sources of it must agree before anything is bound:
      // what was asked for, and what the upstream says it served.
      requireDigest(target, reference, expectedHex, hex);
      requireDigest(
          target,
          reference,
          OciDigest.hexOrNull(response.headers().firstValue(DOCKER_CONTENT_DIGEST).orElse(null)),
          hex);
      // Parsed before it is stored, exactly as the push path does: a body that is not a manifest
      // must not become a cache entry that then serves. `requireReferencesExist` is deliberately
      // NOT applied — see the class javadoc on lazy children.
      parsed = manifestParser.parse(bytes, contentType(response));
    } catch (RuntimeException refused) {
      blobStore.discard(staged);
      throw refused;
    }

    blobStore.promote(staged);
    registry.bindManifest(target.name(), reference, hex, parsed.mediaType(), bytes.length);
    if (!OciDigest.isDigest(reference)) {
      registry.recordMirrorTagCheck(target.name(), reference, Instant.now());
    }

    LOG.infof(
        "mirror: fetched manifest %s@%s from %s (%d bytes, %s)",
        target.name().full(), reference, target.upstreamDomain(), bytes.length, parsed.mediaType());
    return new OciRegistryService.StoredManifest(hex, parsed.mediaType(), bytes.length);
  }

  // --- blobs ------------------------------------------------------------------------------------

  /**
   * Makes sure a blob is stored, fetching it from the upstream if it is not.
   *
   * <p>Streams straight through {@link BlobStore#stage}, so the digest is computed <b>as the bytes
   * arrive</b> and a gigabyte never materialises in heap — the same discipline every pushed layer
   * goes through, and the reason nothing here has to trust the upstream. A stream that does not hash
   * to what was asked for is discarded and refused: no row, no chunk, nothing bound.
   *
   * <p>A no-op when the namespace has no registered upstream; the route then answers its own 404.
   * A {@code HEAD} fetches too, deliberately — a {@code HEAD} answering 404 for a layer this mirror
   * could serve makes a client give up on a pull that would have worked, and the bytes it fetches
   * were about to be asked for anyway.
   */
  public void ensureBlob(OciRegistryService.PullTarget target, String hex) {
    if (target.upstreamDomain() == null || blobStore.exists(hex)) {
      return;
    }
    try {
      fetchBlob(target, hex);
    } catch (Unreachable unreachable) {
      throw upstreamUnreachable(target, "blob", OciDigest.wire(hex));
    }
  }

  private void fetchBlob(OciRegistryService.PullTarget target, String hex) {
    String wireDigest = OciDigest.wire(hex);
    HttpResponse<InputStream> response =
        sendAuthenticated(
            target,
            URI.create(
                apiBase(target) + "/v2/" + target.name().image() + "/blobs/" + wireDigest),
            blobTimeout,
            HttpRequest.Builder::GET,
            HttpResponse.BodyHandlers.ofInputStream());

    if (response.statusCode() == 404) {
      drain(response.body());
      throw new OciException(
          OciCode.BLOB_UNKNOWN,
          "this mirror has no cached copy, and "
              + target.upstreamDomain()
              + " has no such blob either",
          Map.of(
              "digest", wireDigest,
              "namespace", target.name().repository(),
              "upstream", target.upstreamDomain()));
    }
    if (response.statusCode() != 200) {
      drain(response.body());
      throw upstreamAnswered(target, response.statusCode(), wireDigest);
    }

    BlobStore.StagedBlob staged;
    try (InputStream body = response.body()) {
      staged = blobStore.stage(body, maxLayerSize.asLongValue());
    } catch (IOException truncated) {
      throw new Unreachable(truncated);
    }

    if (!staged.sha256().equals(hex)) {
      // The staging is this caller's to drop — the store never discards one it was not told to.
      // Discarded rather than promoted under its own true digest: a push comes from inside
      // qits-net, an upstream does not, and bytes nobody asked for are not worth the rows.
      blobStore.discard(staged);
      throw new OciException(
          OciCode.DIGEST_INVALID,
          502,
          target.upstreamDomain() + " served bytes that do not hash to the digest requested",
          Map.of("expected", wireDigest, "actual", OciDigest.wire(staged.sha256())));
    }
    blobStore.promote(staged);
    LOG.infof(
        "mirror: fetched blob %s for %s from %s (%d bytes)",
        wireDigest, target.name().full(), target.upstreamDomain(), staged.size());
  }

  // --- the wire -------------------------------------------------------------------------------

  /**
   * Sends a request bare, and once more carrying a token if the upstream challenges for one.
   *
   * <p>Bare first because two of the three launch upstreams never challenge for a public pull, and
   * a client that asked for a token unprompted would pay a round trip per scope for nothing. One
   * retry, never two: a second 401 after a token means the token is not the problem, and looping
   * would turn a misconfiguration into load on somebody else's registry.
   */
  private <T> HttpResponse<T> sendAuthenticated(
      OciRegistryService.PullTarget target,
      URI uri,
      Duration timeout,
      java.util.function.UnaryOperator<HttpRequest.Builder> shape,
      HttpResponse.BodyHandler<T> bodyHandler) {

    HttpResponse<T> response = send(shape.apply(HttpRequest.newBuilder(uri).timeout(timeout)), bodyHandler);
    if (response.statusCode() != 401) {
      return response;
    }
    MirrorBearerTokens.Challenge challenge =
        MirrorBearerTokens.parseChallenge(
            response.headers().firstValue("www-authenticate").orElse(null));
    if (challenge == null) {
      return response;
    }
    drainIfStream(response);

    String token =
        tokens.token(
            http, challenge, "repository:" + target.name().image() + ":pull", manifestTimeout);
    if (token == null) {
      return response;
    }
    return send(
        shape
            .apply(HttpRequest.newBuilder(uri).timeout(timeout))
            .header("Authorization", "Bearer " + token),
        bodyHandler);
  }

  private <T> HttpResponse<T> send(
      HttpRequest.Builder builder, HttpResponse.BodyHandler<T> bodyHandler) {
    try {
      return http.send(builder.build(), bodyHandler);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Unreachable(interrupted);
    } catch (Exception unreachable) {
      // A refused connection, a DNS failure, a TLS failure, and — the one this bound exists for —
      // a request that ran past its timeout because the upstream accepted it and then went quiet.
      throw new Unreachable(unreachable);
    }
  }

  private URI manifestUri(OciRegistryService.PullTarget target, String reference) {
    return URI.create(
        apiBase(target) + "/v2/" + target.name().image() + "/manifests/" + reference);
  }

  private String apiBase(OciRegistryService.PullTarget target) {
    return MirrorEndpoints.apiBase(target.upstreamDomain(), endpointOverride.orElse(null));
  }

  // --- failures -------------------------------------------------------------------------------

  /**
   * The cold-miss-with-the-upstream-down answer: {@code 502}, in the npm proxy's words.
   *
   * <p>Not a 404: nothing here knows whether the reference exists, and telling a puller "no such
   * manifest" when the truth is "I could not ask" sends them to debug the wrong registry. Not a 500
   * either — a network miss is not this service failing.
   */
  private OciException upstreamUnreachable(
      OciRegistryService.PullTarget target, String what, String reference) {
    return new OciException(
        OciCode.UNSUPPORTED,
        502,
        "upstream registry "
            + target.upstreamDomain()
            + " is unreachable and this "
            + what
            + " is not cached",
        Map.of(
            "reference", reference,
            "namespace", target.name().repository(),
            "upstream", target.upstreamDomain()));
  }

  /** An upstream that answered, but not with content — a 5xx, a rate limit, an odd redirect. */
  private OciException upstreamAnswered(
      OciRegistryService.PullTarget target, int status, String reference) {
    LOG.warnf(
        "mirror: %s answered %d for %s@%s",
        target.upstreamDomain(), status, target.name().full(), reference);
    return new OciException(
        OciCode.UNSUPPORTED,
        502,
        "upstream registry " + target.upstreamDomain() + " answered " + status,
        Map.of(
            "reference", reference,
            "namespace", target.name().repository(),
            "upstream", target.upstreamDomain(),
            "status", status));
  }

  private void requireDigest(
      OciRegistryService.PullTarget target, String reference, String expected, String actual) {
    if (expected == null || expected.equals(actual)) {
      return;
    }
    throw new OciException(
        OciCode.DIGEST_INVALID,
        502,
        target.upstreamDomain() + " served a manifest that does not hash to the digest expected",
        Map.of(
            "reference", reference,
            "expected", OciDigest.wire(expected),
            "actual", OciDigest.wire(actual)));
  }

  private static String contentType(HttpResponse<?> response) {
    return response.headers().firstValue("content-type").orElse(null);
  }

  /**
   * Reads a whole small document, refusing anything past the cap.
   *
   * <p>{@code BodyHandlers.ofByteArray} would be shorter and unbounded: a manifest route that
   * trusted an upstream's {@code Content-Length}, or its absence, is a heap exhaustion an operator
   * cannot configure their way out of.
   */
  private static byte[] readCapped(InputStream in, long cap) throws IOException {
    byte[] bytes = in.readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE));
    if (bytes.length > cap) {
      throw new OciException(
          OciCode.SIZE_INVALID, "upstream manifest exceeds " + cap + " bytes");
    }
    return bytes;
  }

  private static HttpRequest.BodyPublisher noBody() {
    return HttpRequest.BodyPublishers.noBody();
  }

  private static void drainIfStream(HttpResponse<?> response) {
    if (response.body() instanceof InputStream stream) {
      drain(stream);
    }
  }

  /** A response body left unread holds its connection out of the pool; a 404's is never wanted. */
  private static void drain(InputStream body) {
    if (body == null) {
      return;
    }
    try (InputStream closing = body) {
      closing.readAllBytes();
    } catch (IOException ignored) {
      // best effort: the connection is the only thing at stake and it is about to be dropped
    }
  }
}
