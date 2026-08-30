package eu.wohlben.qits.artifacts.control;

import static eu.wohlben.qits.artifacts.control.BlobStoreTest.noise;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The staging sweep: what replaces emptying an old temp directory.
 *
 * <p>A process that dies mid-upload leaves committed {@code STAGING} chunks behind, because chunk
 * writes are autocommit by design — that is the price of never holding a transaction open across a
 * gigabyte, and this is how it is paid back.
 */
@QuarkusTest
class BlobStagingSweepTest extends ArtifactsTestSupport {

  @Inject BlobStore blobStore;

  @Test
  void anAbandonedStagingAreaOlderThanTheTtlIsReclaimed() {
    BlobStore.StagedBlob orphan = blobStore.stage(new ByteArrayInputStream(noise(300, 41)), 1 << 20);
    backdateStaging(orphan.contentId(), Duration.ofDays(2));
    assertEquals(1, stagingCount());

    // The set of open stages is process-local: a stage cannot outlive the JVM holding its running
    // digest, so after a restart nothing is protected and the sweep is free to take it. Forgetting
    // it here is what a restart does.
    forget(orphan);

    assertEquals(1, blobStore.sweepAbandonedStaging());
    assertEquals(0, stagingCount());
    assertEquals(0, chunkCount(), "the cascade takes the chunks with the content");
  }

  @Test
  void aStagingAreaThisProcessStillHoldsOpenIsNeverCut() {
    // An upload slower than the TTL is unusual, not wrong. Cutting it would turn a slow client into
    // a corrupted blob, which is the one failure mode a content-addressed store cannot detect.
    BlobStore.StagedBlob live = blobStore.stage(new ByteArrayInputStream(noise(300, 42)), 1 << 20);
    backdateStaging(live.contentId(), Duration.ofDays(2));

    assertEquals(0, blobStore.sweepAbandonedStaging(), "an open stage is not litter");
    assertEquals(1, stagingCount());

    assertEquals(false, blobStore.promote(live), "and it still promotes normally afterwards");
    assertTrue(blobStore.exists(live.sha256()));
  }

  @Test
  void aYoungAbandonedStagingAreaIsLeftAlone() {
    BlobStore.StagedBlob recent = blobStore.stage(new ByteArrayInputStream(noise(300, 43)), 1 << 20);
    forget(recent);

    assertEquals(0, blobStore.sweepAbandonedStaging(), "inside the TTL it is an upload, not litter");
    assertEquals(1, stagingCount());

    blobStore.discard(recent);
  }

  @Test
  void theSweepNeverTouchesPromotedContent() {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(noise(300, 44)), 1 << 20);
    blobStore.promote(staged);
    // Old enough by every measure the sweep could use, and still not its business: the grace window
    // is what governs stored content, and only delete() may act on it.
    backdateStaging(staged.contentId(), Duration.ofDays(400));

    assertEquals(0, blobStore.sweepAbandonedStaging());
    assertTrue(blobStore.exists(staged.sha256()));
  }

  /**
   * Drops the store's in-process record of an open stage, the way a restart would — the only way a
   * test can reach the abandoned case, since the live process protects everything it opened.
   */
  private void forget(BlobStore.StagedBlob staged) {
    blobStore.forgetOpenStage(staged.contentId());
  }
}
