package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The maven case of qits-platform-artifacts' ArtifactAccessTrackerTest, moved with its tracker. */
@QuarkusTest
class MavenAccessTrackerTest extends ArtifactsTestSupport {

  @Inject MavenAccessTracker tracker;
  @Inject ArtifactRepositoryService repositoryService;

  private static final Instant FIRST = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void aMavenFileReadTouchesOnePathAndCoalesces() {
    repositoryService.ensure("maven", MavenPackagesProfile.KEY);
    persistMavenArtifact("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar");
    persistMavenArtifact("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.pom");

    tracker.touchMavenArtifact("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar", FIRST);
    assertEquals(
        FIRST,
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar")
            .orElseThrow().accessedAt);
    assertNull(
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.pom")
            .orElseThrow().accessedAt);

    tracker.touchMavenArtifact(
        "maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar", FIRST.plusSeconds(3599));
    mavenArtifacts.getEntityManager().clear();
    assertEquals(
        FIRST,
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar")
            .orElseThrow().accessedAt);

    tracker.touchMavenArtifact(
        "maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar", FIRST.plusSeconds(3600));
    mavenArtifacts.getEntityManager().clear();
    assertEquals(
        FIRST.plusSeconds(3600),
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar")
            .orElseThrow().accessedAt);
  }

  private void persistMavenArtifact(String repository, String path) {
    QuarkusTransaction.requiringNew().run(() -> {
      MavenArtifact row = new MavenArtifact();
      row.repository = repository;
      row.path = path;
      row.blobId = "b".repeat(64);
      row.sizeBytes = 1;
      row.createdAt = FIRST.minusSeconds(10);
      mavenArtifacts.persist(row);
    });
  }
}
