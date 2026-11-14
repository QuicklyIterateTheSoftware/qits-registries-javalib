package eu.wohlben.qits.blobstore.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.BlobCompaction.CompactionResult;
import eu.wohlben.qits.blobstore.testdb.BlobTables;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The library on a consumer that has not migrated, and then the migration itself.
 *
 * <p>The blob datasource points at a database built from the FROZEN pre-segment DDL ({@link
 * PreSegmentsProfile}). Ordered, because the story is: the library bump ships first and must work
 * there unchanged; then the consumer runs {@code db/blobstore-segments-upgrade.sql} under the running
 * store — this very process, with its detection already cached — and nothing stored before may be
 * lost, while what is stored after lands in segments and compaction can finally return disk.
 */
@QuarkusTest
@TestProfile(PreSegmentsProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BlobPreSegmentsTest extends SegmentTestSupport {

  @Test
  @Order(1)
  void onThePreSegmentSchemaTheStoreWorksExactlyAsBefore() throws Exception {
    assertEquals(0, count("select count(*) from pg_class where relname = 'blob_segment'"));

    String kept = store(3 * SegmentedProfile.CHUNK + 17, 100);
    String gone = store(2 * SegmentedProfile.CHUNK, 101);
    assertServedIntact(kept);
    assertEquals(4, count("select count(*) from blob_chunk where content_id = '" + contentOf(kept) + "'"));
    reclaim(gone);
    assertFalse(blobStore.exists(gone));

    CompactionResult result = compaction.compact();

    assertEquals(CompactionResult.State.UNSEGMENTED, result.state());
    assertTrue(result.blobTablesBytesBefore() > 0, "the disk figures are measured all the same");
    assertEquals(result.blobTablesBytesBefore(), result.blobTablesBytesAfter());
    assertTrue(result.databaseBytesBefore() > 0);
    assertEquals(0, result.segmentsDrained() + result.segmentsDropped() + result.segmentsHeld());
    assertEquals(0, result.bytesMoved());
    assertEquals(0, count("select count(*) from pg_class where relname = 'blob_segment'"));
    assertServedIntact(kept);
  }

  @Test
  @Order(2)
  void theUpgradeRewritesNothingLosesNothingAndThenCompacts() throws Exception {
    List<String> stored = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      stored.add(store(2 * SegmentedProfile.CHUNK, 200 + i));
    }
    long heapFile = count("select pg_relation_filenode('blob_chunk')");
    long toastFile =
        count(
            "select pg_relation_filenode(reltoastrelid) from pg_class"
                + " where oid = 'blob_chunk'::regclass");

    // The consumer's migration, as Flyway would run it: the shipped file, one transaction.
    try (Connection connection = blobs.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement sql = connection.createStatement()) {
        for (String statement : BlobTables.statements(BlobTables.SEGMENTS_UPGRADE)) {
          sql.execute(statement);
        }
      }
      connection.commit();
      connection.setAutoCommit(true);
    }

    // The old table IS segment 0 — same files, so not one byte of blob content was rewritten.
    assertEquals(heapFile, count("select pg_relation_filenode('blob_chunk_0')"));
    assertEquals(
        toastFile,
        count(
            "select pg_relation_filenode(reltoastrelid) from pg_class"
                + " where oid = 'blob_chunk_0'::regclass"));
    assertEquals(1, openSegment());
    for (String blobId : stored) {
      assertEquals(0, segmentOfBlob(blobId));
      assertServedIntact(blobId);
    }

    // This process detected the old schema and cached it. Its next chunk insert omits the segment,
    // fails, detects again and lands in the open segment — no restart needed.
    String after = store(2 * SegmentedProfile.CHUNK, 300);
    assertEquals(1, segmentOfBlob(after));
    assertServedIntact(after);

    // A gc sweep reclaims three quarters of what was stored before the upgrade. Segment 0 is
    // closed and a quarter live, so the next run drains it and drops the old table outright.
    List<String> kept = new ArrayList<>();
    for (int i = 0; i < stored.size(); i++) {
      if (i % 4 == 0) {
        kept.add(stored.get(i));
      } else {
        reclaim(stored.get(i));
      }
    }
    CompactionResult result = compaction.compact();

    assertEquals(CompactionResult.State.COMPACTED, result.state());
    assertTrue(result.segmentsDropped() >= 1, result.toString());
    assertFalse(partitionExists(0), "the pre-segment table is gone, and its files with it");
    assertTrue(result.blobTablesBytesReturned() > 0, result.toString());
    kept.add(after);
    for (String blobId : kept) {
      assertTrue(segmentOfBlob(blobId) >= 1);
      assertServedIntact(blobId);
    }
    try (InputStream in = blobStore.open(after)) {
      assertEquals(2 * SegmentedProfile.CHUNK, in.readAllBytes().length);
    }
  }
}
