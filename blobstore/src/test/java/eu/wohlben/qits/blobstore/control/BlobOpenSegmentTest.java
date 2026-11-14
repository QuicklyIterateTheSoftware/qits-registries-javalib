package eu.wohlben.qits.blobstore.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Writers that do not name a segment, and writers caught by a roll.
 *
 * <p>The writer that does not name one is a library older than qits-2248. Consumers deploy
 * start-first, so the previous instance keeps serving uploads after the new instance's Flyway has
 * run the segment upgrade, inserting {@code (content_id, seq, bytes)} exactly as it always did. The
 * column default {@code blob_open_segment()} is what keeps those pushes working — so the statement
 * here is that library's, verbatim, through raw JDBC.
 */
@QuarkusTest
@TestProfile(SegmentedProfile.class)
class BlobOpenSegmentTest extends SegmentTestSupport {

  /** What a pre-qits-2248 library sends for every chunk. */
  static final String OLD_LIBRARY_INSERT =
      "insert into blob_chunk (content_id, seq, bytes) values (?, ?, ?)";

  /** What this library sends: the open segment named in the statement itself. */
  static final String SEGMENTED_INSERT =
      "insert into blob_chunk (content_id, seq, bytes, segment)"
          + " values (?, ?, ?, (select segment from blob_segment))";

  @Inject BlobSegments segments;

  @Test
  void anInsertThatNamesNoSegmentLandsInTheOpenOne() throws Exception {
    UUID content = stagingContent();
    insertChunk(OLD_LIBRARY_INSERT, content, 0);
    assertEquals(openSegment(), segmentOf(content));

    // And it follows the open segment as the store rolls, rather than being pinned to one.
    int before = openSegment();
    fillUntilClosed(before, 8000);
    UUID later = stagingContent();
    insertChunk(OLD_LIBRARY_INSERT, later, 0);
    assertTrue(openSegment() > before);
    assertEquals(openSegment(), segmentOf(later));
  }

  @ParameterizedTest
  @ValueSource(strings = {OLD_LIBRARY_INSERT, SEGMENTED_INSERT})
  void anInsertCaughtByARollWaitsForItAndLandsInTheNewSegment(String insert) throws Exception {
    // A real roll, held open at its last moment — the new partition attached, blob_segment moved,
    // nothing committed — while an insert is sent. The insert must queue behind the roll (not fail
    // with "no partition of relation found for row", which is what a writer holding the parent's
    // old partition list met before the roll blocked writers) and then land in the new segment.
    int from = openSegment();
    UUID content = stagingContent();
    ExecutorService writer = Executors.newSingleThreadExecutor();
    try {
      Future<?>[] insertion = new Future<?>[1];
      boolean rolled =
          segments.roll(
              from,
              () -> {
                insertion[0] = writer.submit(() -> insertChunk(insert, content, 0));
                awaitInsertWaitingOnALock();
              });
      assertTrue(rolled);
      insertion[0].get(30, TimeUnit.SECONDS);
    } finally {
      writer.shutdownNow();
    }

    assertEquals(from + 1, openSegment());
    assertEquals(from + 1, segmentOf(content), "the insert lands in the segment the roll opened");
  }

  /** A STAGING content for raw chunks to hang off — the foreign key wants one. */
  private UUID stagingContent() {
    UUID content = UUID.randomUUID();
    update(
        "insert into blob_content (content_id, state, started_at) values (?, 'STAGING', now())",
        statement -> statement.setObject(1, content));
    return content;
  }

  private Void insertChunk(String sql, UUID content, int seq) throws Exception {
    try (Connection connection = blobs.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, content);
      statement.setInt(2, seq);
      statement.setBytes(3, BlobStoreTest.noise(1024, seq));
      statement.executeUpdate();
    }
    return null;
  }

  /** Polls the server until the insert is parked on a lock, so the roll commits with it queued. */
  private void awaitInsertWaitingOnALock() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (count(
              "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                  + " and query like 'insert into blob_chunk%'")
          > 0) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("the insert never queued behind the roll's locks");
  }
}
