package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The store's own half of access tracking. The npm, maven, OCI, daemon and docs cases that shared
 * this suite went with their formats — the per-format trackers in qits-registries carry them.
 */
@QuarkusTest
class ArtifactAccessTrackerTest extends ArtifactsTestSupport {

  @Inject ArtifactAccessTracker tracker;
  @Inject ArtifactRepositoryService repositoryService;

  private static final Instant FIRST = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void aContentReadTouchesEveryMatchingRecordButNoOtherRepository() {
    repositoryService.ensure("shots", CiScreenshotsProfile.KEY);
    repositoryService.ensure("other", CiScreenshotsProfile.KEY);
    persistRecord("shots", "same");
    persistRecord("shots", "same");
    persistRecord("other", "same");

    tracker.touchArtifact("shots", "same", FIRST);

    assertEquals(2, records.list("repository", "shots").stream()
        .filter(row -> FIRST.equals(row.accessedAt)).count());
    assertNull(records.list("repository", "other").getFirst().accessedAt);
  }

  @Test
  void writesAreCoalescedUntilTheTimestampIsOneHourOld() {
    repositoryService.ensure("shots", CiScreenshotsProfile.KEY);
    persistRecord("shots", "blob");
    tracker.touchArtifact("shots", "blob", FIRST);
    tracker.touchArtifact("shots", "blob", FIRST.plusSeconds(3599));
    records.getEntityManager().clear();
    assertEquals(FIRST, records.find("blobId", "blob").firstResult().accessedAt);

    tracker.touchArtifact("shots", "blob", FIRST.plusSeconds(3600));
    records.getEntityManager().clear();
    assertEquals(FIRST.plusSeconds(3600), records.find("blobId", "blob").firstResult().accessedAt);
  }

  private void persistRecord(String repository, String blob) {
    QuarkusTransaction.requiringNew().run(() -> {
      ArtifactRecord row = new ArtifactRecord();
      row.id = UUID.randomUUID().toString();
      row.repository = repository;
      row.blobId = blob;
      row.mediatype = "image/png";
      row.size = 1;
      row.createdAt = FIRST.minusSeconds(10);
      records.persist(row);
    });
  }
}
