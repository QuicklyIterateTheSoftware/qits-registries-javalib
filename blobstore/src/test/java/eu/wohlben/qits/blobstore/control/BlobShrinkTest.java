package eu.wohlben.qits.blobstore.control;

import static eu.wohlben.qits.blobstore.control.ShrinkProfile.BUDGET;
import static eu.wohlben.qits.blobstore.control.ShrinkProfile.CHUNK;
import static eu.wohlben.qits.blobstore.control.ShrinkProfile.SEGMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.BlobCompaction.CompactionResult;
import eu.wohlben.qits.blobstore.control.BlobSegments.ShrinkReport;
import eu.wohlben.qits.blobstore.testdb.BlobTables;
import eu.wohlben.qits.blobstore.testdb.EmbeddedPg;
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
import org.junit.jupiter.api.AfterEach;
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

  /**
   * Grants the suite's role USAGE on {@code pg_toast}, as the platform's deployer grants each
   * service role in its own database, then drops the segments the base reset emptied, so each case
   * sees only its own. The case that runs without the grant revokes it itself.
   */
  @BeforeEach
  void grantToastUsageAndDropEmptiedSegments() throws Exception {
    toastUsage(true);
    compaction.compact();
  }

  /** The suite's role as it is created — a table owner with no USAGE on {@code pg_toast}. */
  @AfterEach
  void revokeToastUsage() throws Exception {
    toastUsage(false);
  }

  /**
   * Grants or revokes USAGE on {@code pg_toast} for {@link EmbeddedPg#USER} in the blob database —
   * through the superuser, the one connection that may, and never the way the store connects.
   */
  private static void toastUsage(boolean grant) throws Exception {
    try (Connection admin = EmbeddedPg.connectAsSuperuser(BlobTables.url());
        Statement sql = admin.createStatement()) {
      sql.execute(
          grant
              ? "grant usage on schema pg_toast to " + EmbeddedPg.USER
              : "revoke usage on schema pg_toast from " + EmbeddedPg.USER);
    }
  }

  /** A big closed segment: its blobs, in the order their bytes sit in its TOAST relation. */
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
    // The tail is a budget of TOAST pages; the truncation reaches down to the last live tuple, so it
    // can also take a dead blob or two just below the tail, and never much more.
    assertTrue(
        before - after >= BUDGET - CHUNK && before - after <= BUDGET + 4L * CHUNK,
        "one run returns about a budget: " + before + " -> " + after + ", " + result);
    assertTrue(result.bytesTruncated() >= BUDGET - CHUNK, result.toString());
    assertTrue(
        result.bytesMoved() > 0 && result.bytesMoved() <= BUDGET + CHUNK,
        "only the tail's live chunks move: " + result);
    assertTrue(result.blobTablesBytesAfter() < result.blobTablesBytesBefore(), result.toString());

    // qits-2248: the one INFO line a shrink reports must carry VACUUM VERBOSE's own account of
    // what it did — the only way to tell "VACUUM truncated" apart from "VACUUM skipped a lock" or
    // "nothing was removable yet" live, since a plain byte count cannot.
    ShrinkReport report = segments.lastShrinkReport();
    assertNotNull(report, "a shrink must leave a report behind");
    assertEquals(big.segment(), report.segment());
    assertTrue(
        report.vacuumSummary() != null && !report.vacuumSummary().isBlank(),
        "the vacuum part of the report must not be empty: " + report);
    assertTrue(
        report.vacuumSummary().toLowerCase().contains("truncat"),
        "a run that truncated pages must say so: " + report);
    assertEquals(0, report.notOwnedStaging(), "nothing staged is in this test's tail");

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
      // One in five live, the first four of each five dead: whatever head the shrinks leave, it
      // ends on a live blob and is a fifth live, so the ratio rule drains it once it is small.
      if (i % 5 == 4) {
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

    // qits-2248: cause (d) — live tail content with no owning blob — must show up in the report as
    // a non-zero "not owned", split out as STAGING, so a live run can tell this apart from (a)-(c).
    ShrinkReport report = segments.lastShrinkReport();
    assertNotNull(report, "a shrink must leave a report behind");
    assertTrue(report.notOwnedStaging() > 0, "the staging upload's chunks must count as not owned: " + report);
    assertEquals(0, report.notOwnedOther(), report.toString());

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
   * qits-2248, found live: the shrink reads the partition's TOAST relation, which a table's owner
   * may not without USAGE on {@code pg_toast} — and a stand-in that needed no such grant, the heap's
   * own order, moved a gigabyte per run and gave back almost nothing. So a role without the grant
   * must leave an oversized segment exactly as it is: nothing moved, nothing vacuumed, no failure.
   */
  @Test
  void withoutUsageOnPgToastAnOversizedSegmentIsLeftUntouched() throws Exception {
    Big big = bigSegment(60_000, Set.of());
    for (int i = 0; i < BLOBS; i += 2) {
      reclaim(big.blobs().get(i).sha256());
    }
    toastUsage(false);
    long before = diskBytes(big.segment());

    CompactionResult result = compaction.compact();

    assertEquals(CompactionResult.State.COMPACTED, result.state());
    assertEquals(0, result.segmentsShrunk(), result.toString());
    assertEquals(0, result.segmentsDrained(), result.toString());
    assertEquals(0, result.bytesMoved(), "nothing moves without the precise tail: " + result);
    assertEquals(0, result.bytesTruncated(), result.toString());
    assertEquals(before, diskBytes(big.segment()), "the segment is not touched");
    assertEquals(big.segment(), segmentOfBlob(big.blobs().get(BLOBS - 1).sha256()));

    // The grant is the whole difference: with it, the next run shrinks the same segment.
    toastUsage(true);
    CompactionResult granted = compaction.compact();
    assertEquals(1, granted.segmentsShrunk(), granted.toString());
    assertTrue(diskBytes(big.segment()) < before, granted.toString());
    for (int i = 1; i < BLOBS; i += 2) {
      assertServedIntact(big.blobs().get(i).sha256());
    }
  }

  /**
   * The tail is read by a TID range scan of the TOAST relation — its last pages and no others —
   * and by the suite's role, the tables' owner and no superuser, with only the USAGE grant: the
   * shape of a service role in production, which a superuser connection would hide.
   */
  @Test
  void theTailIsReadByATidRangeScanAsTheTablesOwnerNotASuperuser() throws Exception {
    Big big = bigSegment(50_000, Set.of());
    String partition = "blob_chunk_" + big.segment();
    BlobSegments.Toast toast = segments.toastOf(partition);
    assertNotNull(toast);
    assertTrue(toast.pages() > 0);
    String tail =
        BlobSegments.tailValuesSql(toast.name(), toast.pages() - BUDGET / toast.blockSize());
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
      try (ResultSet rows = sql.executeQuery("explain " + tail)) {
        while (rows.next()) {
          plan.append(rows.getString(1)).append('\n');
        }
      }
      assertTrue(plan.toString().contains("Tid Range Scan"), "reads the tail pages only:\n" + plan);
      try (ResultSet rows = sql.executeQuery(tail)) {
        assertTrue(rows.next(), "the granted owner reads its own TOAST tail");
      }
    }
  }

  /**
   * qits-2248, found live: VACUUM's {@code INDEX_CLEANUP AUTO} default bypasses index vacuuming
   * whenever fewer than {@code BYPASS_THRESHOLD_PAGES} (2% of the relation's pages) carry an {@code
   * LP_DEAD} line pointer — on segment 0 a tail the shrink had just emptied of every live TOAST
   * value still truncated nothing, because the bypassed pointers stayed and truncation will not cut
   * a page that still holds one. Forcing {@code INDEX_CLEANUP ON} is the fix; see {@link
   * BlobSegments#vacuumSql} and {@code shrinkTail}'s javadoc for the live numbers.
   *
   * <p>A genuine bypass needs PostgreSQL's own thresholds to line up: the 2% bypass trigger is a
   * share of the <em>whole</em> relation's pages, not of the window being vacuumed, so the relation
   * has to outweigh one shrink budget by about 1/0.02 = 50x before a budget's worth of freshly-dead
   * pages can still read as under 2%. At this profile's 8 MiB budget that is a relation of at least
   * ~400 MiB — a hundred times {@link ShrinkProfile}'s 4 MiB segment and ten times the 40 MiB this
   * suite's biggest fixture stages today — well past what a unit test should insert and VACUUM just
   * to exercise one branch. So no such case is added here; what a unit test can prove instead is
   * the statement itself forces the option, and that an ordinary shrink's VACUUM VERBOSE account
   * never says "bypassed".
   */
  @Test
  void theVacuumForcesIndexCleanupOnSoABypassCannotLeaveLinePointersBehind() throws Exception {
    assertTrue(
        BlobSegments.vacuumSql("blob_chunk_0").toLowerCase().contains("index_cleanup on"),
        "the shrink's VACUUM must force index cleanup on, not leave it at AUTO's default");

    Big big = bigSegment(70_000, Set.of());
    for (int i = 0; i < BLOBS; i++) {
      if (i % 3 == 1) {
        reclaim(big.blobs().get(i).sha256());
      }
    }

    compaction.compact();

    ShrinkReport report = segments.lastShrinkReport();
    assertNotNull(report, "a shrink must leave a report behind");
    assertFalse(
        report.vacuumSummary().toLowerCase().contains("bypassed"),
        "index cleanup forced on must never let VACUUM bypass index vacuuming: " + report);
  }
}
