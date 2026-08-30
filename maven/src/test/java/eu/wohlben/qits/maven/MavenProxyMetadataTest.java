package eu.wohlben.qits.maven;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import jakarta.inject.Inject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What happens to a cached {@code maven-metadata.xml} once its TTL is up — the half {@code
 * MavenProxyTest} cannot reach, because proving expiry against the shipped hour would mean an
 * hour-long test.
 *
 * <p>{@code metadata-ttl=PT0S} makes every request expired on arrival, which turns four distinct
 * claims into fast assertions: expiry <b>revalidates</b> rather than refetches, it revalidates with
 * {@code Last-Modified} when upstream offers no {@code ETag}, a new upstream version becomes
 * visible, and an unreachable upstream serves the stale copy instead of failing. That last one is
 * half of why the proxy exists at all — a Central outage must not stop a build.
 */
@QuarkusTest
@TestProfile(MavenProxyMetadataTest.ExpiredOnArrival.class)
class MavenProxyMetadataTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = Long.toHexString(System.nanoTime());

  private static final String PROXY = "central";
  private static final String GROUP_PATH = "org/example";
  private static final String GROUP_ID = "org.example";

  public static class ExpiredOnArrival implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.artifacts.maven.proxy.upstream", StubMavenRepository.INSTANCE.baseUrl(),
          "qits.artifacts.maven.proxy.metadata-ttl", "PT0S");
    }
  }

  @TestHTTPResource("/")
  URL root;

  /** The repository row through the service — the admin endpoint stayed with the service module. */
  @Inject ArtifactRepositoryService repositoryService;

  @BeforeEach
  void ensureRepositoryAndUpstream() {
    repositoryService.ensure(PROXY, MavenProxyProfile.KEY);
    StubMavenRepository.INSTANCE.reset();
  }

  @Test
  void anExpiredDocumentIsRevalidatedRatherThanRefetched() {
    String path = upstream("1.0.0");

    try (MavenClient maven = client()) {
      maven.getText(PROXY, path);
      assertEquals(0, StubMavenRepository.INSTANCE.conditionalRequests(), "nothing to validate yet");

      HttpResponse<String> again = maven.getText(PROXY, path);
      assertEquals(200, again.statusCode());
      assertTrue(again.body().contains("<version>1.0.0</version>"), again.body());
      assertEquals(
          1,
          StubMavenRepository.INSTANCE.conditionalRequests(),
          "the second request must carry If-None-Match from the stored ETag");
    }
  }

  @Test
  void anUpstreamWithNoEtagIsRevalidatedWithLastModifiedInstead() {
    // Maven repositories are older than universal ETag support: a mirror behind a plain file server
    // answers Last-Modified and nothing else. Storing only the etag would refetch the whole document
    // on every expiry there, silently, with the cache still looking like it worked.
    StubMavenRepository.INSTANCE.withEtags(false);
    String path = upstream("1.0.0");

    try (MavenClient maven = client()) {
      maven.getText(PROXY, path);
      HttpResponse<String> again = maven.getText(PROXY, path);

      assertEquals(200, again.statusCode());
      assertEquals(
          1,
          StubMavenRepository.INSTANCE.conditionalRequests(),
          "the older validator is stored and replayed too");
    }
  }

  @Test
  void aNewUpstreamVersionAppearsOnceTheTtlHasPassed() {
    // The reason this document has a TTL at all and an artifact path does not: this one mutates.
    String path = upstream("1.0.0");
    String artifactId = artifactIdOf(path);

    try (MavenClient maven = client()) {
      assertTrue(maven.getText(PROXY, path).body().contains("<version>1.0.0</version>"));

      StubMavenRepository.INSTANCE.hostMetadata(
          path, StubMavenRepository.metadataDocument(GROUP_ID, artifactId, "1.0.0", "1.1.0"));

      String refreshed = maven.getText(PROXY, path).body();
      assertTrue(refreshed.contains("<version>1.1.0</version>"), refreshed);
      assertTrue(refreshed.contains("<version>1.0.0</version>"), "the old version stays resolvable");
    }
  }

  @Test
  void theDerivedChecksumFollowsTheDocumentAcrossARevalidation() {
    // The consistency claim, held across the one event that could break it. A proxied checksum would
    // be upstream's hash of the NEW document while the cache still served the old one; a derived one
    // moves with whatever is served, every time.
    String path = upstream("1.0.0");
    String artifactId = artifactIdOf(path);

    try (MavenClient maven = client()) {
      String before = maven.getText(PROXY, path).body();
      String checksumBefore = maven.getText(PROXY, path + ".sha1").body().trim();
      assertEquals(TinyArtifact.hex(before.getBytes(StandardCharsets.UTF_8), "SHA-1"), checksumBefore);

      StubMavenRepository.INSTANCE.hostMetadata(
          path, StubMavenRepository.metadataDocument(GROUP_ID, artifactId, "1.0.0", "2.0.0"));

      String after = maven.getText(PROXY, path).body();
      String checksumAfter = maven.getText(PROXY, path + ".sha1").body().trim();
      assertNotEquals(before, after, "the fixture only means anything while the document moved");
      assertEquals(TinyArtifact.hex(after.getBytes(StandardCharsets.UTF_8), "SHA-1"), checksumAfter);
    }
  }

  @Test
  void anUnreachableUpstreamServesTheStaleCopy() {
    String path = upstream("1.0.0");

    try (MavenClient maven = client()) {
      assertEquals(200, maven.getText(PROXY, path).statusCode());

      StubMavenRepository.INSTANCE.reachable(false);
      HttpResponse<String> stale = maven.getText(PROXY, path);
      assertEquals(200, stale.statusCode());
      assertTrue(
          stale.body().contains("<version>1.0.0</version>"),
          "a Central outage must not stop a resolve of something already seen");
    }
  }

  @Test
  void anUnreachableUpstreamAndNothingCachedIsA502() {
    StubMavenRepository.INSTANCE.reachable(false);
    try (MavenClient maven = client()) {
      assertEquals(
          502,
          maven.getText(PROXY, GROUP_PATH + "/never-seen-" + UNIQUE.incrementAndGet()
                  + "/maven-metadata.xml")
              .statusCode());
    }
  }

  @Test
  void aDocumentUpstreamDoesNotHaveIs404() {
    try (MavenClient maven = client()) {
      assertEquals(
          404,
          maven.getText(PROXY, GROUP_PATH + "/absent-" + UNIQUE.incrementAndGet()
                  + "/maven-metadata.xml")
              .statusCode());
    }
  }

  // --- fixture ----------------------------------------------------------------------------------

  /** Hosts one artifact's metadata upstream and answers with the path it is served from. */
  private String upstream(String version) {
    String artifactId = "revalidated-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/maven-metadata.xml";
    StubMavenRepository.INSTANCE.hostMetadata(
        path, StubMavenRepository.metadataDocument(GROUP_ID, artifactId, version));
    return path;
  }

  private static String artifactIdOf(String metadataPath) {
    String withoutFile = metadataPath.substring(0, metadataPath.lastIndexOf('/'));
    return withoutFile.substring(withoutFile.lastIndexOf('/') + 1);
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
