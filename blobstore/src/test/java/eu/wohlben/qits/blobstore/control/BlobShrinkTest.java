package eu.wohlben.qits.blobstore.control;

import static eu.wohlben.qits.blobstore.control.ShrinkProfile.BUDGET;
import static eu.wohlben.qits.blobstore.control.ShrinkProfile.CHUNK;
import static eu.wohlben.qits.blobstore.control.ShrinkProfile.SEGMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.BlobCompaction.CompactionResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-2248, found live: an oversized closed segment — segment 0 after the upgrade, 20 GB and 92%
 * live on dev — is never drained whole, so without a shrink every blob deleted from it returned
 * nothing. Compaction cuts such a segment down from its tail, a budget per run, whatever its live
 * share.
 */
@QuarkusTest
@TestProfile(ShrinkProfile.class)
class BlobShrinkTest extends SegmentTestSupport {

  private static final int BLOBS = 40;

  @Inject BlobSegments segments;

  /** Drops the segments the base reset emptied, so each case sees only its own. */
  @BeforeEach
  void dropEmptiedSegments() {
    compaction.compact();
  }

  /**
   * A big closed segment: its blobs, in the order their rows sit in its heap and their bytes in its
   * TOAST relation.
   */
  private record Big(int segment, List<BlobStore.StagedBlob> blobs) {}

  /**
   * Stages {@link #BLOBS} 1 MiB blobs into one fresh segment — staging never rolls, so they all
   * land there, appended in order — then promotes all but {@code staying} and closes the segment.
   */
  private Big bigSegment(int seedBase, Set<Integer> staying) {
    segments.roll(openSegment());
    int segment = openSegment();
    List<BlobStore.StagedBlob> staged = new ArrayList<>();
    for (int i = 0; i < BLOBS; i++) {
      staged.add(
          blobStore.stage(
              new ByteArrayInputStream(BlobStoreTest.noise(CHUNK, seedBase + i)), Long.MAX_VALUE));
    }
    for (int i = 0; i < BLOBS; i++) {
      if (!staying.contains(i)) {
        assertFalse(blobStore.promote(staged.get(i)));
      }
    }
    if (openSegment() == segment) {
      segments.roll(segment);
    }
    assertTrue(openSegment() > segment, "the big segment must be closed");
    assertTrue(
        diskBytes(segment) > 8 * SEGMENT, "the segment must be far past twice the segment size");
    return new Big(segment, staged);
  }

  private long diskBytes(int segment) {
    return count("select pg_total_relation_size('blob_chunk_" + segment + "')");
  }

  private long blobTablesBytes() {
    return count(
        "select (select sum(pg_total_relation_size(relid)) from pg_partition_tree('blob_chunk'))"
            + " + pg_total_relation_size('blob') + pg_total_relation_size('blob_content')"
            + " + pg_total_relation_size('blob_segment')");
  }

  @Test
  void oneRunCutsAboutOneBudgetOffTheTailOfAPartlyDeadSegment() throws Exception {
    Big big = bigSegment(10_000, Set.of());
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < BLOBS; i++) {
      String blobId = big.blobs().get(i).sha256();
      if (i % 3 == 1) {
        reclaim(blobId);
      } else {
        kept.add(blobId);
      }
    }
    long before = diskBytes(big.segment());

    CompactionResult result = compaction.compact();

    long after = diskBytes(big.segment());
    assertEquals(CompactionResult.State.COMPACTED, result.state());
    assertEquals(1, result.segmentsShrunk(), result.toString());
    assertEquals(0, result.segmentsDrained(), "an oversized segment is never drained whole");
    assertTrue(partitionExists(big.segment()));
    // The budget counts the live chunks that move, and two in three are live, so the tail spans
    // about one and a half budgets — the dead third between them comes back for free. The
    // truncation reaches down to the last live tuple, so it can also take a dead blob just below
    // the tail, and never much more.
    assertTrue(
        before - after >= BUDGET * 3 / 2 - CHUNK && before - after <= BUDGET * 3 / 2 + 2L * CHUNK,
        "one run returns about a budget: " + before + " -> " + after + ", " + result);
    assertTrue(result.bytesTruncated() >= BUDGET - CHUNK, result.toString());
    assertTrue(
        result.bytesMoved() > 0 && result.bytesMoved() <= BUDGET + CHUNK,
        "only the tail's live chunks move: " + result);
    assertTrue(result.blobTablesBytesAfter() < result.blobTablesBytesBefore(), result.toString());

    String last = big.blobs().get(BLOBS - 1).sha256();
    String first = big.blobs().get(0).sha256();
    assertNotEquals(big.segment(), segmentOfBlob(last), "the tail's blobs moved out");
    assertEquals(big.segment(), segmentOfBlob(first), "the head's blobs stayed");
    for (String blobId : kept) {
      assertServedIntact(blobId);
    }
  }

  @Test
  void repeatedRunsEmptyAndDropTheSegmentAndTheDiskFallsToTheLiveData() throws Exception {
    Big big = bigSegment(20_000, Set.of());
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < BLOBS; i++) {
      String blobId = big.blobs().get(i).sha256();
      // The budget counts live bytes, so a tail of three budgets' worth must be live for three
      // shrinks: the top 24 are, and of the head below them one in five, the first four of each
      // five dead. The last run takes what is left of the head, which empties the segment.
      if (i >= BLOBS - 3 * (int) (BUDGET / CHUNK) || i % 5 == 4) {
        kept.add(blobId);
      } else {
        reclaim(blobId);
      }
    }
    long live = (long) kept.size() * CHUNK;

    long moved = 0;
    int shrunk = 0;
    int runs = 0;
    while (partitionExists(big.segment()) && runs < 12) {
      long sizeBefore = diskBytes(big.segment());
      CompactionResult result = compaction.compact();
      runs++;
      moved += result.bytesMoved();
      shrunk += result.segmentsShrunk();
      if (partitionExists(big.segment())) {
        assertTrue(
            diskBytes(big.segment()) < sizeBefore, "every run must take something off: " + result);
      }
    }

    assertFalse(partitionExists(big.segment()), "emptied and dropped within " + runs + " runs");
    assertTrue(shrunk >= 3, "shrunk a budget at a time first, " + shrunk + " in " + runs + " runs");
    assertEquals(live, moved, "every live chunk moved exactly once, and nothing else");
    // Left: the live chunks in the segments they moved into (each up to a segment plus a blob), with
    // their TOAST overhead, heaps and indexes.
    assertTrue(
        blobTablesBytes() <= live + 2 * SEGMENT,
        "the blob tables fall to about the live data: " + blobTablesBytes() + " for " + live);
    for (String blobId : kept) {
      assertServedIntact(blobId);
    }
  }

  @Test
  void aFullyLiveOversizedSegmentStillShrinks() throws Exception {
    // The live case: segment 0 was 92% live, which the ratio rule never touches.
    Big big = bigSegment(30_000, Set.of());
    long before = diskBytes(big.segment());

    CompactionResult result = compaction.compact();

    assertEquals(1, result.segmentsShrunk(), result.toString());
    assertTrue(before - diskBytes(big.segment()) >= BUDGET - CHUNK, result.toString());
    assertTrue(
        result.bytesMoved() >= BUDGET - CHUNK && result.bytesMoved() <= BUDGET + CHUNK,
        "a live tail moves about a budget: " + result);
    for (BlobStore.StagedBlob blob : big.blobs()) {
      assertServedIntact(blob.sha256());
    }
  }

  @Test
  void aStagingUploadInTheTailStaysAndHoldsOnlyWhatIsBelowIt() throws Exception {
    // Low enough in the tail that what lies above it is worth a truncation to VACUUM: it truncates
    // only when at least 1000 pages or a sixteenth of the relation can go.
    int staging = BLOBS - 6;
    Big big = bigSegment(40_000, Set.of(staging));
    BlobStore.StagedBlob upload = big.blobs().get(staging);
    long before = diskBytes(big.segment());

    CompactionResult result = compaction.compact();

    assertEquals(CompactionResult.State.COMPACTED, result.state());
    assertEquals(big.segment(), segmentOf(upload.contentId()), "STAGING chunks are never moved");
    assertNotEquals(
        big.segment(), segmentOfBlob(big.blobs().get(BLOBS - 1).sha256()), "the rest moved");
    long cut = before - diskBytes(big.segment());
    assertTrue(
        cut > 0 && cut < BUDGET - CHUNK,
        "truncated down to the upload's bytes and no further: " + cut + ", " + result);

    // Promoted, it is ordinary content: the next run moves it and the tail goes on shrinking.
    assertFalse(blobStore.promote(upload));
    long held = diskBytes(big.segment());
    CompactionResult next = compaction.compact();
    assertNotEquals(big.segment(), segmentOfBlob(upload.sha256()));
    assertTrue(diskBytes(big.segment()) < held, next.toString());
    for (BlobStore.StagedBlob blob : big.blobs()) {
      assertServedIntact(blob.sha256());
    }
  }

  /**
   * qits-2248, found live: the first cut of the shrink read the partition's TOAST relation, which
   * a superuser may and the service's role — the tables' owner, not a superuser — may not. The
   * suite connects as such a role; this pins that it still does, and that the tail is chosen from
   * the partition's own heap.
   */
  @Test
  void theTailIsChosenFromTheHeapByTheTablesOwnerNotASuperuser() throws Exception {
    Big big = bigSegment(50_000, Set.of());
    String partition = "blob_chunk_" + big.segment();
    try (Connection connection = blobs.getConnection();
        Statement sql = connection.createStatement()) {
      try (ResultSet role =
          sql.executeQuery(
              "select r.rolsuper, c.relowner = r.oid from pg_roles r, pg_class c"
                  + " where r.rolname = current_user and c.oid = '"
                  + partition
                  + "'::regclass")) {
        assertTrue(role.next());
        assertFalse(role.getBoolean(1), "the suite must not connect as a superuser");
        assertTrue(role.getBoolean(2), "the suite's role owns the blob tables, as in production");
      }
      StringBuilder plan = new StringBuilder();
      try (ResultSet rows =
          sql.executeQuery(
              "explain "
                  + BlobSegments.tailChunksSql(partition).replace("?", String.valueOf(BUDGET)))) {
        while (rows.next()) {
          plan.append(rows.getString(1)).append('\n');
        }
      }
      assertTrue(plan.toString().contains("on " + partition), "reads the heap:\n" + plan);
      assertFalse(plan.toString().contains("pg_toast"), "reads no TOAST relation:\n" + plan);
    }
  }
}
