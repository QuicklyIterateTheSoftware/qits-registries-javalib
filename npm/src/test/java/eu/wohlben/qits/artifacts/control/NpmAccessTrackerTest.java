package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The npm case of qits-platform-artifacts' ArtifactAccessTrackerTest, moved with its tracker. */
@QuarkusTest
class NpmAccessTrackerTest extends ArtifactsTestSupport {

  @Inject NpmAccessTracker tracker;
  @Inject ArtifactRepositoryService repositoryService;

  private static final Instant FIRST = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void anNpmTarballReadTouchesOneVersionInOneRepositoryAndCoalesces() {
    // One table serves both npm types, so the scope assertion is what proves a proxy pull of the
    // same coordinate does not age the hosted row (or the reverse).
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    repositoryService.ensure("npmjs", NpmProxyProfile.KEY);
    persistNpmVersion("npm", "@qits/ui", "1.0.0");
    persistNpmVersion("npm", "@qits/ui", "1.0.1");
    persistNpmVersion("npmjs", "@qits/ui", "1.0.0");

    tracker.touchNpmVersion("npm", "@qits/ui", "1.0.0", FIRST);
    assertEquals(FIRST, npmVersions.findOne("npm", "@qits/ui", "1.0.0").orElseThrow().accessedAt);
    assertNull(npmVersions.findOne("npm", "@qits/ui", "1.0.1").orElseThrow().accessedAt);
    assertNull(npmVersions.findOne("npmjs", "@qits/ui", "1.0.0").orElseThrow().accessedAt);

    tracker.touchNpmVersion("npm", "@qits/ui", "1.0.0", FIRST.plusSeconds(3599));
    npmVersions.getEntityManager().clear();
    assertEquals(FIRST, npmVersions.findOne("npm", "@qits/ui", "1.0.0").orElseThrow().accessedAt);

    tracker.touchNpmVersion("npm", "@qits/ui", "1.0.0", FIRST.plusSeconds(3600));
    npmVersions.getEntityManager().clear();
    assertEquals(
        FIRST.plusSeconds(3600),
        npmVersions.findOne("npm", "@qits/ui", "1.0.0").orElseThrow().accessedAt);
  }

  private void persistNpmVersion(String repository, String packageName, String version) {
    QuarkusTransaction.requiringNew().run(() -> {
      NpmVersion row = new NpmVersion();
      row.repository = repository;
      row.packageName = packageName;
      row.version = version;
      row.tarballBlobId = "a".repeat(64);
      row.manifestJson = "{}";
      row.createdAt = FIRST.minusSeconds(10);
      npmVersions.persist(row);
    });
  }
}
