package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The OCI case of qits-platform-artifacts' ArtifactAccessTrackerTest, moved with its tracker. */
@QuarkusTest
class OciAccessTrackerTest extends ArtifactsTestSupport {

  @Inject OciAccessTracker tracker;
  @Inject ArtifactRepositoryService repositoryService;

  private static final Instant FIRST = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void aTagPullTouchesTagAndManifestWhileADigestPullCanTouchOnlyManifest() {
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    persistOci();

    tracker.touchManifest("qits", "app", "digest", "latest", FIRST);
    assertEquals(FIRST, ociManifests.findOne("qits", "app", "digest").orElseThrow().accessedAt);
    assertEquals(FIRST, ociTags.findOne("qits", "app", "latest").orElseThrow().accessedAt);

    Instant later = FIRST.plusSeconds(7200);
    tracker.touchManifest("qits", "app", "digest", null, later);
    ociManifests.getEntityManager().clear();
    assertEquals(later, ociManifests.findOne("qits", "app", "digest").orElseThrow().accessedAt);
    assertEquals(FIRST, ociTags.findOne("qits", "app", "latest").orElseThrow().accessedAt);
  }

  private void persistOci() {
    QuarkusTransaction.requiringNew().run(() -> {
      OciManifest manifest = new OciManifest();
      manifest.repository = "qits";
      manifest.imageName = "app";
      manifest.digest = "digest";
      manifest.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
      manifest.size = 1;
      manifest.createdAt = FIRST.minusSeconds(10);
      ociManifests.persist(manifest);
      OciTag tag = new OciTag();
      tag.repository = "qits";
      tag.imageName = "app";
      tag.tag = "latest";
      tag.manifestDigest = "digest";
      tag.updatedAt = FIRST.minusSeconds(10);
      ociTags.persist(tag);
    });
  }
}
