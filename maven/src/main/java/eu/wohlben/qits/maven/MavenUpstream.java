package eu.wohlben.qits.maven;

import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.MavenRegistryService;
import eu.wohlben.qits.artifacts.error.MavenException;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@code MAVEN_PROXY} miss path: everything the maven wire does that leaves the deployment.
 *
 * <p>{@code NpmUpstream}'s shape, because the fact is the same — two documents with opposite cache
 * semantics:
 *
 * <ul>
 *   <li>{@code maven-metadata.xml} <b>mutates</b> — a new version appears upstream with nothing here
 *       changing — so it is cached with a TTL and revalidated with {@code If-None-Match} /
 *       {@code If-Modified-Since}. When upstream cannot be reached the stale copy is served anyway:
 *       a build keeps resolving through a Central outage, which is half of why the proxy exists.
 *   <li>Everything else is an <b>immutable path</b>, so a hit never leaves the process and a miss is
 *       fetched once and cached forever. It streams through {@link BlobStore#stage} — hashing while
 *       streaming, for free — so a large jar never materialises in heap.
 * </ul>
 *
 * <p><b>Nothing here verifies upstream's checksums, deliberately.</b> Upstream's own {@code .sha1}
 * and friends are cached as ordinary immutable paths and served back untouched, and the maven client
 * verifies the jar against them end to end — so the proxy cannot silently corrupt an artifact even
 * in principle. Deriving those checksums locally instead would be worse than useless: a hash this
 * service computed from bytes this service downloaded agrees with itself whatever arrived, which
 * removes the client's check while looking like it kept it. The hosted repository derives checksums
 * for the opposite reason — there the bytes <em>are</em> ours, and there is no upstream copy to
 * disagree with.
 *
 * <p><b>Bounded, and with no retries.</b> Two timeouts sized by what is being fetched, exactly as
 * the npm proxy and the OCI mirror size theirs: a document is small XML, an artifact can be tens of
 * megabytes over a slow link. This sits inside a request on a worker thread, so a hung upstream must
 * cost one bounded wait and never pin the thread.
 */
@ApplicationScoped
public class MavenUpstream {

  private static final Logger LOG = Logger.getLogger(MavenUpstream.class);

  /** Small XML from a CDN. Long enough for a slow link, short enough that a hang is bounded. */
  private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(30);

  /** A jar can be tens of megabytes over a slow link, and a partial fetch is not a cache entry. */
  private static final Duration ARTIFACT_TIMEOUT = Duration.ofMinutes(10);

  /**
   * An <b>instance</b> field, not a static one, for the reason spelled out on {@code NpmUpstream}'s
   * and {@code PostReceiveNotifier}'s: a static {@code HttpClient} is built by the class
   * initialiser, which under GraalVM runs at image-build time, and native-image then refuses the
   * image over an {@code HttpClientFacade} in the heap. {@code @ApplicationScoped} still means one
   * client per process — just one created when the process starts rather than when it was compiled.
   */
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  @Inject MavenRegistryService registry;
  @Inject BlobStore blobStore;

  @ConfigProperty(
      name = "qits.artifacts.maven.proxy.upstream",
      defaultValue = "https://repo1.maven.org/maven2")
  String upstream;

  @ConfigProperty(name = "qits.artifacts.maven.proxy.metadata-ttl", defaultValue = "PT1H")
  Duration metadataTtl;

  @ConfigProperty(name = "qits.artifacts.maven.max-artifact-size", defaultValue = "512M")
  MemorySize maxArtifactSize;

  /**
   * The upstream {@code maven-metadata.xml} at this path, from cache when fresh and from upstream
   * otherwise.
   *
   * <p>Returned <b>as upstream wrote it</b>, and unlike a packument it needs no rewrite: maven
   * metadata carries versions, not URLs. Those are also the bytes the derived checksum siblings are
   * computed from, which is what makes them consistent by construction.
   */
  public byte[] metadata(String repository, String path) {
    Optional<MavenRegistryService.CachedMetadata> cached =
        registry.findProxyMetadata(repository, path);

    if (cached.isPresent()
        && cached.get().fetchedAt().isAfter(Instant.now().minus(metadataTtl))) {
      return bytes(cached.get().doc());
    }

    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path)).timeout(METADATA_TIMEOUT).GET();
    cached.ifPresent(
        entry -> {
          if (entry.etag() != null && !entry.etag().isBlank()) {
            request.header("If-None-Match", entry.etag());
          } else if (entry.lastModified() != null && !entry.lastModified().isBlank()) {
            // The older validator, and the reason both are stored: a maven repository behind a plain
            // file server answers Last-Modified and no etag, and revalidating with nothing at all
            // would refetch the document on every expiry.
            request.header("If-Modified-Since", entry.lastModified());
          }
        });

    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return serveStaleOrFail(cached, path, interrupted);
    } catch (Exception unreachable) {
      return serveStaleOrFail(cached, path, unreachable);
    }

    if (response.statusCode() == 304 && cached.isPresent()) {
      registry.touchProxyMetadata(
          repository, path, etagOf(response), lastModifiedOf(response), Instant.now());
      return bytes(cached.get().doc());
    }
    if (response.statusCode() == 200) {
      String doc = response.body();
      // Checked before it is stored, the npm precedent: a body that is not a metadata document must
      // not become a cache entry that then serves for the whole TTL. A shape check rather than a
      // parse — the wire never reads a field of this document, so parsing it would buy a dependency
      // and an XML parser's native-image configuration for nothing.
      requireMetadataShape(doc, path);
      registry.storeProxyMetadata(
          repository, path, doc, etagOf(response), lastModifiedOf(response), Instant.now());
      return bytes(doc);
    }
    if (response.statusCode() == 404 && cached.isEmpty()) {
      throw new MavenException(404, "no metadata upstream at " + path);
    }
    // Anything else — a 5xx, a rate limit, an unexpected redirect loop — is upstream having a bad
    // day, which is exactly the case the stale copy exists for.
    return serveStaleOrFail(
        cached, path, new MavenException(502, "upstream answered " + response.statusCode()));
  }

  /**
   * Pulls one immutable path through, records it, and returns the row the serve path reads from — so
   * that path stays <b>one</b> code path for both maven types.
   *
   * <p>Upstream's checksum files come through here too. They are immutable paths like any other, and
   * caching them rather than deriving them is what keeps the client's verification end to end; see
   * the class javadoc.
   */
  public MavenRegistryService.StoredArtifact fetchArtifact(String repository, String path) {
    BlobStore.StagedBlob staged;
    try {
      HttpResponse<InputStream> response =
          http.send(
              HttpRequest.newBuilder(uri(path)).timeout(ARTIFACT_TIMEOUT).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() == 404) {
        // The one status that is not an upstream failure: upstream was asked and has no such path.
        // Collapsing this into the 502 below would send whoever is debugging a failed build to the
        // wrong repository — the mirror's rule, and the most expensive wrong answer this service
        // can give.
        drain(response);
        throw new MavenException(404, "no such artifact upstream: " + path);
      }
      if (response.statusCode() != 200) {
        drain(response);
        throw new MavenException(
            502, "upstream answered " + response.statusCode() + " for " + path);
      }
      try (InputStream body = response.body()) {
        staged = blobStore.stage(body, maxArtifactSize.asLongValue());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MavenException(502, "interrupted fetching " + path);
    } catch (MavenException already) {
      throw already;
    } catch (eu.wohlben.qits.blobstore.error.PayloadTooLargeException tooLarge) {
      // NOT "unreachable": upstream answered and streamed until the size cap tripped. Collapsing
      // this into the catch below cost 2026-09-01 an evening — every userflows build 502ed on the
      // 201M playwright driver-bundle while the message sent everyone to the network.
      throw new MavenException(
          413,
          path
              + " is larger than qits.artifacts.maven.max-artifact-size ("
              + maxArtifactSize.asLongValue()
              + " bytes) and was not cached — raise the cap to proxy it");
    } catch (Exception unreachable) {
      LOG.debugf(unreachable, "maven proxy: could not fetch %s", path);
      throw new MavenException(
          502, "upstream maven repository is unreachable and " + path + " is not cached");
    }
    blobStore.promote(staged);

    registry.recordProxiedArtifact(repository, path, staged.sha256(), staged.size());

    return registry
        .findArtifact(repository, path)
        .orElseThrow(() -> new MavenException(500, "the path just written is not readable back"));
  }

  private byte[] serveStaleOrFail(
      Optional<MavenRegistryService.CachedMetadata> cached, String path, Throwable why) {
    if (cached.isPresent()) {
      LOG.debugf(why, "maven proxy: serving %s stale — upstream unavailable", path);
      return bytes(cached.get().doc());
    }
    throw new MavenException(
        502, "upstream maven repository is unreachable and " + path + " is not cached");
  }

  /**
   * The cheapest check that says "this is a metadata document and not an error page". A CDN's 200
   * with an HTML body is the failure this catches, and it is worth catching at the door: without it
   * the page becomes the cache entry and every resolve inside the TTL reads it.
   */
  private static void requireMetadataShape(String doc, String path) {
    String trimmed = doc == null ? "" : doc.trim();
    if (!trimmed.startsWith("<") || !trimmed.contains("<metadata")) {
      throw new MavenException(502, "upstream returned no maven metadata document at " + path);
    }
  }

  private URI uri(String path) {
    return URI.create(base() + "/" + path);
  }

  private String base() {
    return upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
  }

  private static byte[] bytes(String doc) {
    return doc.getBytes(StandardCharsets.UTF_8);
  }

  private static String etagOf(HttpResponse<?> response) {
    return response.headers().firstValue("etag").orElse(null);
  }

  private static String lastModifiedOf(HttpResponse<?> response) {
    return response.headers().firstValue("last-modified").orElse(null);
  }

  /** Releases a non-200 body's connection rather than leaving it to the pool's timeout. */
  private static void drain(HttpResponse<InputStream> response) {
    try (InputStream body = response.body()) {
      body.readAllBytes();
    } catch (Exception ignored) {
      // The status is the answer; a body we are discarding is not worth an error.
    }
  }
}
