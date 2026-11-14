package eu.wohlben.qits.blobstore.control;

import static eu.wohlben.qits.blobstore.control.BlobStoreTest.noise;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * What the segment suites share: blobs of real size, a byte-exact read-back, and the catalog
 * questions — which segment is open, where a content's chunks are, whether a partition exists.
 */
abstract class SegmentTestSupport extends ArtifactsTestSupport {

  /** Past the shipped seven-day grace window, so {@link BlobReclaim#delete} takes the blob. */
  static final Duration AGED = Duration.ofDays(8);

  @Inject BlobStore blobStore;

  @Inject BlobReclaim reclaim;

  @Inject BlobCompaction compaction;

  /** Stages and promotes {@code length} bytes of seeded noise; answers the address. */
  String store(int length, int seed) {
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(noise(length, seed)), Long.MAX_VALUE);
    blobStore.promote(staged);
    return staged.sha256();
  }

  /** Ages a blob past the grace window and reclaims it, the way a consumer's sweep does. */
  void reclaim(String blobId) {
    backdate(blobId, AGED);
    assertEquals(BlobStore.DeleteResult.DELETED, reclaim.delete(blobId, id -> true));
  }

  /** The SHA-256 of what the store serves for this blob, read through the ordinary stream. */
  String servedDigest(String blobId) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = blobStore.open(blobId)) {
      byte[] buffer = new byte[64 * 1024];
      int n;
      while ((n = in.read(buffer)) != -1) {
        digest.update(buffer, 0, n);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Content addressing makes the address the expected digest: equal means byte-identical. */
  void assertServedIntact(String blobId) throws Exception {
    assertEquals(blobId, servedDigest(blobId), "blob " + blobId + " must read back byte-identical");
  }

  int openSegment() {
    return (int) count("select segment from blob_segment");
  }

  boolean partitionExists(int segment) {
    return count("select count(*) from pg_class where relname = 'blob_chunk_" + segment + "'") == 1;
  }

  /** The one segment holding a content's chunks; fails if they are spread over several. */
  int segmentOf(UUID contentId) {
    return (int)
        count(
            "select case when count(distinct segment) = 1 then min(segment) else -1 end"
                + " from blob_chunk where content_id = '"
                + contentId
                + "'");
  }

  int segmentOfBlob(String blobId) {
    return segmentOf(contentOf(blobId));
  }

  UUID contentOf(String blobId) {
    try (Connection connection = blobs.getConnection();
        PreparedStatement select =
            connection.prepareStatement("select content_id from blob where id = ?")) {
      select.setString(1, blobId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getObject(1, UUID.class);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Stores filler until the open segment rolls past {@code segment}, so that segment is closed.
   *
   * @return the fillers' addresses, for the caller to reclaim
   */
  java.util.List<String> fillUntilClosed(int segment, int seedBase) {
    java.util.List<String> fillers = new java.util.ArrayList<>();
    int seed = seedBase;
    while (openSegment() <= segment) {
      fillers.add(store(2 * SegmentedProfile.CHUNK, seed++));
      if (fillers.size() > 50) {
        throw new AssertionError("the open segment never rolled past " + segment);
      }
    }
    return fillers;
  }
}
