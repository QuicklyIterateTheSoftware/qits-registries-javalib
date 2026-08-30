package eu.wohlben.qits.npm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.NpmPackageName;
import eu.wohlben.qits.artifacts.control.NpmRegistryService;
import eu.wohlben.qits.artifacts.error.NpmException;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@code NPM_PROXY} miss path: everything this service does that leaves the deployment.
 *
 * <p>Two documents with opposite cache semantics, which is the whole design:
 *
 * <ul>
 *   <li>A <b>packument mutates</b> — a new version appears upstream with nothing here changing — so
 *       it is cached with a TTL and revalidated with {@code ETag}/{@code If-None-Match}. When
 *       upstream cannot be reached the stale copy is served anyway: CI keeps installing through an
 *       npmjs outage, which is half of why the proxy exists.
 *   <li>A <b>tarball is immutable</b>, so a hit never leaves the process and a miss is fetched once
 *       and cached forever. It streams through {@link BlobStore#stage} — hashing while streaming,
 *       for free — so a large package never materialises in heap.
 * </ul>
 *
 * <p><b>Nothing here verifies upstream's hashes, deliberately.</b> The packument we re-emit carries
 * upstream's {@code integrity} unmodified, and the client verifies the bytes against it end to end —
 * so the proxy cannot silently corrupt a package even in principle, and a mid-flight check here
 * would only add a way for a stale-but-correct upstream document to break an install this service
 * had no business breaking.
 *
 * <p>This is the <b>first outbound TLS</b> in the process, and the plainest possible client is the
 * point: a JDK {@link HttpClient} needs no extension, no reflection registration and no new
 * dependency, so the native binary gets exactly one new thing to be right about. Real-npmjs TLS is
 * unreachable from this repo's test suite by construction (clone-alone, no network — the proxy
 * suite drives an in-process stub), so it takes one manual smoke on a deployment; see the README.
 */
@ApplicationScoped
public class NpmUpstream {

  private static final Logger LOG = Logger.getLogger(NpmUpstream.class);

  /**
   * An <b>instance</b> field, not a static one, for the reason spelled out on {@code
   * PostReceiveNotifier}'s: a static {@code HttpClient} is built by the class initialiser, which
   * under GraalVM runs at image-build time, and native-image then refuses the image over an
   * {@code HttpClientFacade} in the heap. {@code @ApplicationScoped} still means one client per
   * process — just one created when the process starts rather than when it was compiled.
   */
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  @Inject NpmRegistryService registry;
  @Inject BlobStore blobStore;
  @Inject ObjectMapper json;

  @ConfigProperty(name = "qits.artifacts.npm.proxy.upstream", defaultValue = "https://registry.npmjs.org")
  String upstream;

  @ConfigProperty(name = "qits.artifacts.npm.proxy.packument-ttl", defaultValue = "PT5M")
  Duration packumentTtl;

  @ConfigProperty(name = "qits.artifacts.npm.max-publish-size", defaultValue = "32M")
  MemorySize maxTarballSize;

  /**
   * The upstream packument for this package, from cache when fresh and from upstream otherwise.
   *
   * <p>Returned <b>as upstream wrote it</b>: the {@code dist.tarball} rewrite happens in {@link
   * NpmPackuments#rewritten} at serve time, because its target depends on the request, and because
   * the original URLs are what {@link #fetchTarball} needs on a miss.
   */
  public JsonNode packument(String repository, NpmPackageName pkg) {
    Optional<NpmRegistryService.CachedPackument> cached =
        registry.findProxyPackument(repository, pkg.full());

    if (cached.isPresent()
        && cached.get().fetchedAt().isAfter(Instant.now().minus(packumentTtl))) {
      return parse(cached.get().doc());
    }

    HttpRequest.Builder request = HttpRequest.newBuilder(packumentUri(pkg))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/json")
        .GET();
    cached
        .map(NpmRegistryService.CachedPackument::etag)
        .filter(etag -> etag != null && !etag.isBlank())
        .ifPresent(etag -> request.header("If-None-Match", etag));

    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return serveStaleOrFail(cached, pkg, interrupted);
    } catch (Exception unreachable) {
      return serveStaleOrFail(cached, pkg, unreachable);
    }

    if (response.statusCode() == 304 && cached.isPresent()) {
      registry.touchProxyPackument(repository, pkg.full(), etagOf(response), Instant.now());
      return parse(cached.get().doc());
    }
    if (response.statusCode() == 200) {
      String doc = response.body();
      // Parsed before it is stored: a body that is not a packument must not become a cache entry
      // that then serves for the whole TTL.
      JsonNode parsed = parse(doc);
      registry.storeProxyPackument(repository, pkg.full(), doc, etagOf(response), Instant.now());
      return parsed;
    }
    if (response.statusCode() == 404 && cached.isEmpty()) {
      throw new NpmException(404, "no such package upstream: " + pkg.full());
    }
    // Anything else — a 5xx, a rate limit, an unexpected redirect loop — is upstream having a bad
    // day, which is exactly the case the stale copy exists for.
    return serveStaleOrFail(
        cached, pkg, new NpmException(502, "upstream answered " + response.statusCode()));
  }

  /**
   * Pulls one version's tarball through, records it, and returns the row the tarball route serves
   * from — so that route stays <b>one</b> code path for both repository types.
   */
  public NpmRegistryService.StoredVersion fetchTarball(
      String repository, NpmPackageName pkg, String version) {

    JsonNode manifest = packument(repository, pkg).path("versions").path(version);
    if (!manifest.isObject()) {
      throw new NpmException(404, "upstream has no " + pkg.full() + "@" + version);
    }
    JsonNode dist = manifest.path("dist");
    String tarball = dist.path("tarball").asText(null);
    URI source =
        tarball == null || tarball.isBlank()
            // A packument with no dist.tarball is not something npmjs produces, but a private
            // upstream might: falling back to the canonical layout beats a 500.
            ? URI.create(base() + "/" + pkg.full() + "/-/" + pkg.tarballFile(version))
            : URI.create(tarball);

    BlobStore.StagedBlob staged;
    try {
      HttpResponse<InputStream> response =
          http.send(
              HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(10)).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new NpmException(
            502,
            "upstream answered " + response.statusCode() + " for the tarball of " + pkg.full()
                + "@" + version);
      }
      try (InputStream body = response.body()) {
        staged = blobStore.stage(body, maxTarballSize.asLongValue());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new NpmException(502, "interrupted fetching " + pkg.full() + "@" + version);
    } catch (NpmException already) {
      throw already;
    } catch (Exception unreachable) {
      LOG.debugf(unreachable, "npm proxy: could not fetch %s@%s", pkg.full(), version);
      throw new NpmException(
          502,
          "upstream npm registry is unreachable and "
              + pkg.full()
              + "@"
              + version
              + " is not cached");
    }
    blobStore.promote(staged);

    registry.recordProxiedVersion(
        repository,
        pkg.full(),
        version,
        staged.sha256(),
        dist.path("integrity").asText(null),
        dist.path("shasum").asText(null),
        manifest.toString());

    return registry
        .findVersion(repository, pkg.full(), version)
        .orElseThrow(() -> new NpmException(500, "the version just written is not readable back"));
  }

  private JsonNode serveStaleOrFail(
      Optional<NpmRegistryService.CachedPackument> cached, NpmPackageName pkg, Throwable why) {
    if (cached.isPresent()) {
      LOG.debugf(why, "npm proxy: serving %s stale — upstream unavailable", pkg.full());
      return parse(cached.get().doc());
    }
    throw new NpmException(
        502, "upstream npm registry is unreachable and " + pkg.full() + " is not cached");
  }

  /** npm sends a scoped name percent-encoded, and so does this — upstream accepts both. */
  private URI packumentUri(NpmPackageName pkg) {
    return URI.create(base() + "/" + pkg.full().replace("/", "%2f"));
  }

  private String base() {
    return upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
  }

  private static String etagOf(HttpResponse<?> response) {
    return response.headers().firstValue("etag").orElse(null);
  }

  private JsonNode parse(String doc) {
    try {
      return json.readTree(doc);
    } catch (Exception e) {
      throw new NpmException(502, "upstream returned a packument that is not JSON");
    }
  }
}
