package eu.wohlben.qits.artifacts.control;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * The two statements that move blob bytes: write one chunk, read one chunk. Everything else in this
 * package is arithmetic on top of them.
 *
 * <p>Both are autocommit and single-statement — see {@link BlobDb} for why that is the whole
 * transaction story for bytes.
 */
final class BlobChunks {

  private BlobChunks() {}

  /** Appends one chunk. {@code len} may be short: the last chunk of a blob usually is. */
  static void insert(BlobDb db, UUID contentId, int seq, byte[] bytes, int len) {
    byte[] exact = len == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, len);
    db.autocommit(
        "Failed to store a blob chunk",
        connection -> {
          try (PreparedStatement insert =
              connection.prepareStatement(
                  "insert into blob_chunk (content_id, seq, bytes) values (?, ?, ?)")) {
            insert.setObject(1, contentId);
            insert.setInt(2, seq);
            insert.setBytes(3, exact);
            insert.executeUpdate();
          }
          return null;
        });
  }

  /** One chunk's bytes, or null if there is no such chunk. */
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
