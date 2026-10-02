package eu.wohlben.qits.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.registry.ContentHashLedger;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-702: the {@code X-Artifacts-Content-Hash} header on a maven deploy, and what
 * {@code MavenRoutes.deploy} does with it through {@link ContentHashLedger}.
 *
 * <p>{@link RecordingContentHashLedger} stands in for the real {@code JpaContentHashLedger}
 * ({@code qits-artifacts-service}, qits-636) — this suite proves the route's side of the contract,
 * never a table.
 */
@QuarkusTest
class MavenContentHashTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String VALUE = "v1:sha256:" + "a".repeat(64);

  @TestHTTPResource("/")
  URL root;

  @Inject ArtifactRepositoryService repositoryService;
  @Inject RecordingContentHashLedger ledger;

  @BeforeEach
  void ensureRepository() {
    repositoryService.ensure("maven", MavenPackagesProfile.KEY);
  }

  @Test
  void theReleasePomPutRecordsTheHash() {
    String artifact = "pom-records-" + UNIQUE.incrementAndGet();
    String name = "eu.wohlben.qits:" + artifact;
    String base = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0";
    byte[] pom = TinyArtifact.pom("eu.wohlben.qits", artifact, "1.0.0");

    try (MavenClient maven = client()) {
      assertEquals(
          201,
          maven
              .put("maven", base + ".pom", pom, ContentHashLedger.HEADER, VALUE)
              .statusCode());
    }

    assertEquals(VALUE, ledger.valueFor(name, "1.0.0"));
  }

  @Test
  void theJarPutDoesNotRecord() {
    String artifact = "jar-ignored-" + UNIQUE.incrementAndGet();
    String name = "eu.wohlben.qits:" + artifact;
    String base = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0";
    byte[] jar = TinyArtifact.jar("jar ignored " + artifact);

    try (MavenClient maven = client()) {
      assertEquals(
          201,
          maven
              .put("maven", base + ".jar", jar, ContentHashLedger.HEADER, VALUE)
              .statusCode());
    }

    assertNull(
        ledger.valueFor(name, "1.0.0"), "a header on the jar must be ignored, never recorded");
  }

  @Test
  void aMalformedHeaderIsA400AndNothingIsStaged() {
    String artifact = "malformed-" + UNIQUE.incrementAndGet();
    String name = "eu.wohlben.qits:" + artifact;
    String base = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0";
    byte[] pom = TinyArtifact.pom("eu.wohlben.qits", artifact, "1.0.0");

    try (MavenClient maven = client()) {
      HttpResponse<String> response =
          maven.put("maven", base + ".pom", pom, ContentHashLedger.HEADER, "not-a-content-hash");
      assertEquals(400, response.statusCode(), response.body());
      assertTrue(response.body().contains(ContentHashLedger.HEADER), response.body());

      // Nothing was stored: the same path now resolves to a 404, not the pom that would have been
      // staged had validation happened after the body.
      assertEquals(404, maven.get("maven", base + ".pom").statusCode());
    }

    assertNull(ledger.valueFor(name, "1.0.0"));
  }

  @Test
  void noHeaderRecordsNothing() {
    String artifact = "no-header-" + UNIQUE.incrementAndGet();
    String name = "eu.wohlben.qits:" + artifact;
    String base = "eu/wohlben/qits/" + artifact + "/1.0.0/" + artifact + "-1.0.0";
    byte[] pom = TinyArtifact.pom("eu.wohlben.qits", artifact, "1.0.0");

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put("maven", base + ".pom", pom).statusCode());
    }

    assertNull(ledger.valueFor(name, "1.0.0"));
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
