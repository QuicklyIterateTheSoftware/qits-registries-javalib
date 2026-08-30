package eu.wohlben.qits.artifacts.control;

import static eu.wohlben.qits.artifacts.control.BlobStoreTest.noise;
import static eu.wohlben.qits.artifacts.control.BlobStoreTest.sha256;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The two things the git host needs and a stream cannot give: seek-and-read over a stored blob, and
 * a staging area that can be read back while it is being written.
 */
@QuarkusTest
class BlobRandomAccessTest extends ArtifactsTestSupport {

  @Inject BlobStore blobStore;

  @Test
  void aChannelReadsFromAnyPositionAcrossChunkBoundaries() throws Exception {
    byte[] bytes = noise(1000, 11);
    String blobId = promoted(bytes);

    try (SeekableByteChannel channel = blobStore.openChannel(blobId)) {
      assertEquals(bytes.length, channel.size());

      // Straddling a boundary deliberately: 100 bytes from offset 50 spans three 64-byte chunks,
      // and a caller that does not loop must still get all 100.
      channel.position(50);
      ByteBuffer buffer = ByteBuffer.allocate(100);
      assertEquals(100, channel.read(buffer));
      assertArrayEquals(Arrays.copyOfRange(bytes, 50, 150), buffer.array());
      assertEquals(150, channel.position());

      // Backwards, which is the access pattern a pack index drives and a stream forbids.
      channel.position(0);
      ByteBuffer head = ByteBuffer.allocate(10);
      assertEquals(10, channel.read(head));
      assertArrayEquals(Arrays.copyOfRange(bytes, 0, 10), head.array());
    }
  }

  @Test
  void aChannelReadsTheWholeBlobAndThenReportsTheEnd() throws Exception {
    byte[] bytes = noise(300, 12);
    String blobId = promoted(bytes);

    try (SeekableByteChannel channel = blobStore.openChannel(blobId)) {
      ByteBuffer all = ByteBuffer.allocate(bytes.length);
      while (all.hasRemaining() && channel.read(all) > 0) {
        // read fills across chunks, so this loop normally runs once
      }
      assertArrayEquals(bytes, all.array());
      assertEquals(-1, channel.read(ByteBuffer.allocate(8)), "past the end is -1, not an error");

      channel.position(bytes.length + 100);
      assertEquals(-1, channel.read(ByteBuffer.allocate(8)));
    }
  }

  @Test
  void aChannelIsReadOnlyAndClosesCleanly() throws Exception {
    String blobId = promoted(noise(100, 13));
    SeekableByteChannel channel = blobStore.openChannel(blobId);

    assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
    assertThrows(NonWritableChannelException.class, () -> channel.truncate(0));

    assertTrue(channel.isOpen());
    channel.close();
    assertFalse(channel.isOpen());
    assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
    channel.close();
  }

  @Test
  void aScratchBlobIsReadableWhileItIsStillBeingWritten() throws Exception {
    // This is the requirement the whole class exists for: JGit's pack parser reads deltas back out
    // of a pack it has not finished storing. Half the bytes below are chunk rows already, half are
    // still the buffered tail, and a reader must not be able to tell which.
    byte[] bytes = noise(500, 14);

    try (ScratchBlob scratch = blobStore.stageScratch()) {
      scratch.write(bytes, 0, 300);
      assertEquals(300, scratch.size());

      // Below the flush watermark: these bytes are in the database.
      ByteBuffer early = ByteBuffer.allocate(20);
      assertEquals(20, scratch.read(10, early));
      assertArrayEquals(Arrays.copyOfRange(bytes, 10, 30), early.array());

      // Above it: these are the tail, in memory only.
      ByteBuffer tail = ByteBuffer.allocate(8);
      assertEquals(8, scratch.read(290, tail));
      assertArrayEquals(Arrays.copyOfRange(bytes, 290, 298), tail.array());

      assertEquals(-1, scratch.read(300, ByteBuffer.allocate(4)), "past what was written is -1");

      scratch.write(bytes, 300, 200);
      assertEquals(500, scratch.size());
      ByteBuffer late = ByteBuffer.allocate(16);
      assertEquals(16, scratch.read(480, late));
      assertArrayEquals(Arrays.copyOfRange(bytes, 480, 496), late.array());
    }
  }

  @Test
  void aScratchBlobHashesWhatItStagedAndPromotesWithoutCopyingIt() throws Exception {
    // The git host's shape exactly: write the pack, hash it by reading it back, hand the content id
    // to promote. Nothing re-reads the bytes into memory and nothing moves them.
    byte[] bytes = noise(700, 15);
    String expected = sha256(bytes);

    BlobStore.StagedBlob staged;
    try (ScratchBlob scratch = blobStore.stageScratch()) {
      scratch.write(bytes, 0, bytes.length);
      String digest;
      try (InputStream in = scratch.openRead()) {
        digest = sha256(in.readAllBytes());
      }
      assertEquals(expected, digest);
      staged = new BlobStore.StagedBlob(digest, scratch.size(), scratch.contentId());
      assertFalse(blobStore.promote(staged));
    }

    assertTrue(blobStore.exists(expected), "close() after promote must not take the blob away");
    assertEquals(bytes.length, blobStore.size(expected));
    try (InputStream in = blobStore.open(expected)) {
      assertArrayEquals(bytes, in.readAllBytes());
    }
    assertEquals(0, stagingCount());
  }

  @Test
  void sealingIsFinalSoTheChunkArithmeticStaysTrue() {
    // A short chunk in the middle would break `seq = position / chunk_size` for everything after
    // it, so the seal happens once and writing after it is a mistake worth naming.
    try (ScratchBlob scratch = blobStore.stageScratch()) {
      scratch.write(noise(100, 16), 0, 100);
      scratch.openRead();
      assertThrows(IllegalStateException.class, () -> scratch.write(new byte[1], 0, 1));
    }
  }

  @Test
  void anAbandonedScratchBlobLeavesNothing() {
    // A writer that gives up — a push that failed, a tar that would not unpack — must cost the
    // store nothing. This is the temp-file cleanup, moved inside.
    ScratchBlob scratch = blobStore.stageScratch();
    scratch.write(noise(400, 17), 0, 400);
    assertEquals(1, stagingCount());

    scratch.close();

    assertEquals(0, stagingCount());
    assertEquals(0, chunkCount());
    scratch.close();
  }

  private String promoted(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), 1 << 20);
    blobStore.promote(staged);
    return staged.sha256();
  }
}
