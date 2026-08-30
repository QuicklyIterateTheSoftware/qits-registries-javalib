package eu.wohlben.qits.artifacts.control;

import static eu.wohlben.qits.artifacts.control.BlobStoreTest.noise;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the per-blob advisory lock is for.
 *
 * <p>The store used to hold a {@code synchronized} object across promote's check-and-move and
 * delete's check-and-unlink, which was only ever correct because both writers lived in one JVM.
 * {@code pg_advisory_xact_lock} keyed on the content address is the same guarantee, held by the
 * database and therefore true across replicas as well.
 */
@QuarkusTest
class BlobPromoteRaceTest extends ArtifactsTestSupport {

  @Inject BlobStore blobStore;

  @Test
  void twoConcurrentPromotesOfTheSameContentLeaveOneBlobAndOneContent() throws Exception {
    byte[] bytes = noise(400, 51);
    BlobStore.StagedBlob first = blobStore.stage(new ByteArrayInputStream(bytes), 1 << 20);
    BlobStore.StagedBlob second = blobStore.stage(new ByteArrayInputStream(bytes), 1 << 20);
    assertEquals(first.sha256(), second.sha256(), "same bytes, same address, two staging areas");
    assertEquals(2, stagingCount());

    CyclicBarrier together = new CyclicBarrier(2);
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> answers =
          threads.invokeAll(
              List.of(promoting(together, first), promoting(together, second)));
      boolean one = answers.get(0).get();
      boolean other = answers.get(1).get();
      assertTrue(one ^ other, "exactly one promote stores the content; the other is the dedupe");
    } finally {
      threads.shutdownNow();
    }

    assertEquals(0, stagingCount(), "the losing staging area is gone, chunks and all");
    assertEquals(bytes.length, blobStore.size(first.sha256()));
    assertEquals(1, count("select count(*) from blob"));
    assertEquals(1, count("select count(*) from blob_content"));
  }

  private Callable<Boolean> promoting(CyclicBarrier together, BlobStore.StagedBlob staged) {
    return () -> {
      together.await();
      return blobStore.promote(staged);
    };
  }
}
