package eu.wohlben.qits.blobstore.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.BlobCompaction.CompactionResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-2248: deleting a blob frees no disk, and compaction is what gives it back.
 *
 * <p>Run at the production chunk size with 8 MiB segments ({@link SegmentedProfile}), because the
 * whole point is measured on disk and 64-byte chunks never reach the TOAST relation where blob bytes
 * actually live.
 */
@QuarkusTest
@TestProfile(SegmentedProfile.class)
class BlobCompactionTest extends SegmentTestSupport {

  /**
   * The base reset deletes every row, which leaves the closed segments empty but present. One run
   * drops them, so each case starts on the open segment alone — the shape of a fresh install.
   */
  @BeforeEach
  void dropEmptiedSegments() {
    compaction.compact();
  }

  @Test
  void compactionReturnsTheDiskThatDeletesLeftBehind() throws Exception {
    // Forty 2 MiB blobs fill ten 8 MiB segments. Keeping every fourth leaves each closed segment a
    // quarter live — the store a gc sweep leaves behind, holding 20 MiB in 80+ MiB of files.
    List<String> kept = new ArrayList<>();
    List<String> gone = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      String blobId = store(2 * SegmentedProfile.CHUNK, 1000 + i);
      (i % 4 == 0 ? kept : gone).add(blobId);
    }
    assertTrue(openSegment() >= 5, "forty 2 MiB blobs must have rolled through several segments");
    long live = 10L * 2 * SegmentedProfile.CHUNK;

    gone.forEach(this::reclaim);
    CompactionResult result = compaction.compact();

    assertEquals(CompactionResult.State.COMPACTED, result.state());
    assertTrue(
        result.blobTablesBytesBefore() >= 80L * SegmentedProfile.CHUNK,
        "a delete gives nothing back: the tables still hold their peak before the run, "
            + result);
    assertTrue(result.segmentsDrained() >= 1, "quarter-live segments are below the 0.5 ratio");
    assertTrue(result.segmentsDropped() >= result.segmentsDrained(), "every drained one dropped");
    assertTrue(result.bytesMoved() > 0 && result.bytesMoved() <= live, result.toString());
    // What may remain beyond the live bytes: the segment open at the start (never drained, up to a
    // segment plus a blob) and the segment the moved bytes fill. Everything else came back.
    assertTrue(
        result.blobTablesBytesAfter() <= live + 3 * SegmentedProfile.SEGMENT,
        "the blob tables must fall to roughly the live data, " + result);
    assertTrue(
        result.blobTablesBytesReturned() >= 40L * SegmentedProfile.CHUNK,
        "at least half the peak must come back, " + result);
    assertTrue(
        result.databaseBytesAfter() < result.databaseBytesBefore(),
        "and the volume must see it: the database shrank, " + result);

    for (String blobId : kept) {
      assertServedIntact(blobId);
    }
    for (String blobId : gone) {
      assertFalse(blobStore.exists(blobId));
    }
  }

  @Test
  void aMostlyLiveSegmentIsLeftAlone() throws Exception {
    int segment = openSegment();
    List<String> blobsThere = fillUntilClosed(segment, 2000);
    CompactionResult result = compaction.compact();

    assertEquals(0, result.segmentsDrained(), "a fully live segment is not worth moving");
    assertEquals(0, result.bytesMoved());
    assertTrue(partitionExists(segment));
    for (String blobId : blobsThere) {
      assertServedIntact(blobId);
    }
  }

  @Test
  void aBlobReadThroughoutTheDrainOfItsSegmentIsNeverMissingAndNeverCorrupt() throws Exception {
    int segment = openSegment();
    List<String> live = new ArrayList<>();
    List<String> filler = new ArrayList<>();
    int seed = 3000;
    while (openSegment() == segment) {
      // 3 MiB live, 3 MiB filler, alternating — the segment ends half dead or worse.
      live.add(store(3 * SegmentedProfile.CHUNK, seed++));
      filler.add(store(3 * SegmentedProfile.CHUNK, seed++));
    }
    filler.forEach(this::reclaim);
    // Under the 0.5 ratio for certain: delete one live blob too if the alternation landed even.
    if (live.size() > 1) {
      reclaim(live.remove(live.size() - 1));
    }

    AtomicBoolean draining = new AtomicBoolean(true);
    AtomicInteger reads = new AtomicInteger();
    ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
    ExecutorService reader = Executors.newSingleThreadExecutor();
    Future<?> reading =
        reader.submit(
            () -> {
              // Keeps going a few rounds past the drain, so reads after the drop are covered too.
              int after = 0;
              while (draining.get() || after++ < 3) {
                for (String blobId : live) {
                  try {
                    String served = servedDigest(blobId);
                    if (!served.equals(blobId)) {
                      failures.add(blobId + " served corrupt as " + served);
                    }
                  } catch (Exception e) {
                    failures.add(blobId + " failed: " + e);
                  }
                  reads.incrementAndGet();
                }
              }
            });
    CompactionResult result;
    try {
      result = compaction.compact();
    } finally {
      draining.set(false);
      reading.get(60, TimeUnit.SECONDS);
      reader.shutdownNow();
    }

    assertTrue(result.segmentsDrained() >= 1, result.toString());
    assertFalse(partitionExists(segment), "the drained segment is dropped");
    assertTrue(failures.isEmpty(), "every read during the drain must succeed: " + failures);
    assertTrue(reads.get() > live.size(), "the reader must actually have overlapped the run");
    for (String blobId : live) {
      assertNotEquals(segment, segmentOfBlob(blobId), "moved out of the drained segment");
      assertServedIntact(blobId);
    }
  }

  @Test
  void aStagingUploadInAClosedSegmentKeepsItUntilItIsPromoted() throws Exception {
    int segment = openSegment();
    byte[] bytes = BlobStoreTest.noise(2 * SegmentedProfile.CHUNK, 4000);
    BlobStore.StagedBlob upload = blobStore.stage(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
    assertEquals(segment, segmentOf(upload.contentId()));
    fillUntilClosed(segment, 4001).forEach(this::reclaim);

    CompactionResult held = compaction.compact();

    // The upload's chunks are not the store's to move: it may still be writing, and it holds no
    // address yet. So its segment cannot empty, and it stays — counted as held, not as a failure.
    assertTrue(held.segmentsHeld() >= 1, held.toString());
    assertTrue(partitionExists(segment), "a segment with STAGING chunks is never dropped");
    assertEquals(segment, segmentOf(upload.contentId()), "nor are those chunks moved");

    assertFalse(blobStore.promote(upload));
    assertServedIntact(upload.sha256());

    // Promoted, it is ordinary content: the next run drains it and the segment goes.
    CompactionResult drained = compaction.compact();
    assertTrue(drained.segmentsDrained() >= 1, drained.toString());
    assertFalse(partitionExists(segment));
    assertServedIntact(upload.sha256());
  }

  @Test
  void aStagingUploadThatIsReapedLetsItsSegmentGo() {
    int segment = openSegment();
    BlobStore.StagedBlob upload =
        blobStore.stage(
            new ByteArrayInputStream(BlobStoreTest.noise(2 * SegmentedProfile.CHUNK, 5000)),
            Long.MAX_VALUE);
    fillUntilClosed(segment, 5001).forEach(this::reclaim);

    compaction.compact();
    assertTrue(partitionExists(segment), "held while the upload's chunks are there");

    // The other way an upload ends: abandoned and swept. The cascade takes its chunks, the segment
    // is empty, and the next run drops it without moving a byte.
    blobStore.discard(upload);
    CompactionResult result = compaction.compact();
    assertFalse(partitionExists(segment));
    assertTrue(result.segmentsDropped() >= 1, result.toString());
  }

  @Test
  void promotesRacingRollsAndCompactionsNeverLoseAChunk() throws Exception {
    // Best effort, by construction rather than by luck alone: four writers store 1–2 MiB blobs, so
    // the 8 MiB segment rolls every few promotes, while a sweeper reclaims every other blob as soon
    // as it lands and a compactor runs back to back — draining and dropping segments that writers
    // were writing into a moment ago. Whatever interleaving happens, every blob kept must read back
    // whole, and no writer may fail.
    int writers = 4;
    int perWriter = 10;
    ConcurrentLinkedQueue<String> kept = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> disposable = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    AtomicBoolean writing = new AtomicBoolean(true);
    AtomicInteger compactions = new AtomicInteger();
    int segmentAtStart = openSegment();

    ExecutorService threads = Executors.newFixedThreadPool(writers + 2);
    try {
      List<Future<?>> writes = new ArrayList<>();
      for (int w = 0; w < writers; w++) {
        int writer = w;
        writes.add(
            threads.submit(
                () -> {
                  for (int i = 0; i < perWriter; i++) {
                    int seed = 6000 + writer * 100 + i;
                    int length = SegmentedProfile.CHUNK + (seed % 7) * (SegmentedProfile.CHUNK / 7);
                    try {
                      String blobId = store(length, seed);
                      (i % 2 == 0 ? kept : disposable).add(blobId);
                    } catch (Throwable e) {
                      failures.add(e);
                    }
                  }
                }));
      }
      Future<?> sweeper =
          threads.submit(
              () -> {
                while (writing.get() || !disposable.isEmpty()) {
                  String blobId = disposable.poll();
                  if (blobId == null) {
                    Thread.onSpinWait();
                    continue;
                  }
                  try {
                    reclaim(blobId);
                  } catch (Throwable e) {
                    failures.add(e);
                  }
                }
              });
      Future<?> compactor =
          threads.submit(
              () -> {
                while (writing.get()) {
                  try {
                    compaction.compact();
                    compactions.incrementAndGet();
                  } catch (Throwable e) {
                    failures.add(e);
                  }
                }
              });
      for (Future<?> write : writes) {
        write.get(5, TimeUnit.MINUTES);
      }
      writing.set(false);
      sweeper.get(5, TimeUnit.MINUTES);
      compactor.get(5, TimeUnit.MINUTES);
    } finally {
      writing.set(false);
      threads.shutdownNow();
    }

    assertTrue(failures.isEmpty(), "no writer, sweeper or compaction may fail: " + failures);
    assertTrue(openSegment() > segmentAtStart + 2, "the segment must have rolled repeatedly");
    assertTrue(compactions.get() > 0, "and compaction must have run alongside");
    compaction.compact();
    assertEquals(writers * perWriter / 2, kept.size());
    for (String blobId : kept) {
      // Not one segment per blob here: a stage that was writing while another writer's promote
      // rolled spans two, which is exactly the case this test exists to provoke.
      assertServedIntact(blobId);
      long chunks = (blobStore.size(blobId) + SegmentedProfile.CHUNK - 1) / SegmentedProfile.CHUNK;
      assertEquals(
          chunks,
          count("select count(*) from blob_chunk where content_id = '" + contentOf(blobId) + "'"),
          "every chunk exactly once, wherever it sits");
    }
    assertTrue(
        count("select count(distinct content_id) from blob_chunk")
            >= kept.size(),
        "the kept blobs' chunks are all still there");
  }

  @Test
  void everyPartitionKeepsTheChunkBytesOutOfLineAndUncompressed() {
    // STORAGE EXTERNAL is set once on the parent; the roll creates partitions as LIKE ... INCLUDING
    // ALL and attaches them, and they must inherit it, or new segments would quietly start
    // compressing already-compressed layers again.
    fillUntilClosed(openSegment(), 7000);
    assertEquals(
        0,
        count(
            "select count(*) from pg_attribute a join pg_inherits i on i.inhrelid = a.attrelid"
                + " where i.inhparent = 'blob_chunk'::regclass and a.attname = 'bytes'"
                + " and a.attstorage <> 'e'"));
    assertTrue(count("select count(*) from pg_inherits where inhparent = 'blob_chunk'::regclass") >= 2);
  }

  @Test
  void compactionRefusesToRunInsideACallersTransaction() {
    // Every step commits on its own; inside a caller's transaction the drops and their locks would
    // last as long as the caller does. A programming error, so it says so rather than answering.
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .run(() -> compaction.compact()));
  }
}
