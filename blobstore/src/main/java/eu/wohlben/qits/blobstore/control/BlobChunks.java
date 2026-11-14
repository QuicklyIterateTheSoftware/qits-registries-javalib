package eu.wohlben.qits.blobstore.control;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * The two statements that move blob bytes: write one chunk, read one chunk. Everything else in this
 * package is arithmetic on top of them — apart from compaction, which moves chunks between segments
 * and lives with the segments in {@link BlobSegments}.
 *
 * <p>Both are autocommit and single-statement — see {@link BlobDb} for why that is the whole
 * transaction story for bytes.
 */
final class BlobChunks {

  private BlobChunks() {}

  /**
   * Appends one chunk. {@code len} may be short: the last chunk of a blob usually is.
   *
   * <p>Which segment it lands in is {@link BlobSegments}'s business — the open one, named in the
   * insert statement itself, or none at all on a store that has not been segmented yet.
   */
  static void insert(BlobSegments segments, UUID contentId, int seq, byte[] bytes, int len) {
    byte[] exact = len == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, len);
    segments.insertChunk(contentId, seq, exact);
  }

  /**
   * One chunk's bytes, or null if there is no such chunk.
   *
   * <p>Names no segment, so it probes every partition's key index — one lookup each — and finds the
   * chunk wherever compaction last moved it. A move commits its copy and its delete together, so
   * this single-statement snapshot sees the chunk in exactly one segment.
   */
  static byte[] read(BlobDb db, UUID contentId, int seq) {
    return db.autocommit(
        "Failed to read a blob chunk",
        connection -> {
          try (PreparedStatement select =
              connection.prepareStatement(
                  "select bytes from blob_chunk where content_id = ? and seq = ?")) {
            select.setObject(1, contentId);
            select.setInt(2, seq);
            try (ResultSet found = select.executeQuery()) {
              return found.next() ? found.getBytes(1) : null;
            }
          }
        });
  }
}
