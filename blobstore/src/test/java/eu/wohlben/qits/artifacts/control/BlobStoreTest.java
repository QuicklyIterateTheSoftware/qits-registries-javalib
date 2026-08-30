package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.PayloadTooLargeException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BlobStoreTest extends ArtifactsTestSupport {

  @Inject BlobStore blobStore;

  @Test
  void stagePromoteAndServeRoundTrip() throws Exception {
    byte[] bytes = TestMedia.png(2, 2, 42);
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), 1024);

    assertEquals(sha256(bytes), staged.sha256());
    assertEquals(bytes.length, staged.size());

    assertFalse(blobStore.promote(staged), "fresh content is not a dedupe");
    assertTrue(blobStore.exists(staged.sha256()));
    assertEquals(bytes.length, blobStore.size(staged.sha256()));
    try (InputStream in = blobStore.open(staged.sha256())) {
      assertArrayEquals(bytes, in.readAllBytes());
    }
  }

  @Test
  void contentLongerThanOneChunkSurvivesTheRoundTripByteForByte() throws Exception {
    // The whole store is arithmetic on the chunk size, and every off-by-one in it lives at a
    // boundary — so the fixture is chosen to end in the middle of a chunk, not on one.
    byte[] bytes = noise(4000, 1);
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), 1 << 20);
    assertFalse(blobStore.promote(staged));

    assertEquals(bytes.length, blobStore.size(staged.sha256()));
    try (InputStream in = blobStore.open(staged.sha256())) {
      assertArrayEquals(bytes, in.readAllBytes());
    }
    assertEquals(63, chunkCount(), "4000 bytes at 64 per chunk is 62 full chunks and a remainder");
  }

  @Test
  void contentEndingExactlyOnAChunkBoundaryStoresNoEmptyTail() throws Exception {
    byte[] bytes = noise(64 * 4, 2);
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), 1 << 20);
    assertFalse(blobStore.promote(staged));

    assertEquals(4, chunkCount(), "an exact multiple must not write a zero-length last chunk");
    try (InputStream in = blobStore.open(staged.sha256())) {
      assertArrayEquals(bytes, in.readAllBytes());
    }
  }

  @Test
  void identicalBytesDedupeToOneContent() {
    byte[] bytes = TestMedia.png(2, 2, 7);
    assertFalse(blobStore.promote(blobStore.stage(new ByteArrayInputStream(bytes), 1024)));
    assertTrue(
        blobStore.promote(blobStore.stage(new ByteArrayInputStream(bytes), 1024)),
        "second identical upload dedupes");

    // The redundant staging goes with the dedupe, chunks and all — the analogue of the temp file
    // the old store deleted when it found the destination already there.
    assertEquals(0, stagingCount());
    assertEquals(1, chunkCount(), "one content, one chunk; the second upload stored nothing new");
  }

  @Test
  void aDedupeDoesNotRefreshTheGraceClock() {
    // Parity with the old promote(), which was a no-op when the file existed and so never touched
    // its mtime. Refreshing here would let a busy blob outrun the collector forever.
    String blobId = stored(31);
    backdate(blobId, Duration.ofDays(30));
    Instant aged = blobStore.lastWrittenAt(blobId);

    assertTrue(blobStore.promote(blobStore.stage(new ByteArrayInputStream(TestMedia.png(2, 2, 31)), 1024)));

    assertEquals(aged, blobStore.lastWrittenAt(blobId), "a dedupe must not restart the window");
    assertEquals(BlobStore.DeleteResult.DELETED, blobStore.delete(blobId, id -> true));
  }

  @Test
  void capAbortsOversizedStream() {
    byte[] bytes = TestMedia.png(2, 2, 9);
    assertThrows(
        PayloadTooLargeException.class, () -> blobStore.stage(new ByteArrayInputStream(bytes), 4));
    assertEquals(0, stagingCount(), "the refused upload must leave no staging behind");
  }

  @Test
  void appendingInChunksGivesTheSameDigestAsOneShot() {
    // The OCI upload session spans three HTTP requests, so the registry cannot use stage(): it
    // needs a handle that survives between them. What must not change is the answer.
    byte[] bytes = noise(500, 3);
    String oneShot = blobStore.stage(new ByteArrayInputStream(bytes), 1 << 20).sha256();

    int third = bytes.length / 3;
    try (BlobStore.IncrementalStage staged = blobStore.stageIncremental()) {
      staged.append(new ByteArrayInputStream(bytes, 0, third), 1 << 20);
      assertEquals(third, staged.written(), "written() is the offset a resume must continue from");
      staged.append(new ByteArrayInputStream(bytes, third, third), 1 << 20);
      staged.append(new ByteArrayInputStream(bytes, 2 * third, bytes.length - 2 * third), 1 << 20);

      BlobStore.StagedBlob finished = staged.finish();
      assertEquals(oneShot, finished.sha256(), "three appends must hash to the same blob as one");
      assertEquals(bytes.length, finished.size());
      assertFalse(blobStore.promote(finished));
    }
    assertTrue(blobStore.exists(oneShot));
  }

  @Test
  void aCapTrippedMidSessionLeavesNoStagedChunks() {
    // The cap counts the blob's TOTAL size, not one call's contribution, and the staged bytes must
    // go with it — a session that dies holding 900 MB of a rejected layer is the exhaustion path,
    // and the rows are as finite a resource as the disk was.
    byte[] bytes = noise(500, 4);
    BlobStore.IncrementalStage staged = blobStore.stageIncremental();
    staged.append(new ByteArrayInputStream(bytes, 0, 4), 8);
    assertThrows(
        PayloadTooLargeException.class,
        () -> staged.append(new ByteArrayInputStream(bytes, 4, bytes.length - 4), 8));

    assertEquals(0, stagingCount(), "the aborted stage must not leave its content row behind");
    assertEquals(0, chunkCount(), "and the cascade must have taken its chunks");
  }

  @Test
  void aStagedBlobCanBeThrownAwayWithoutPromoting() {
    // The maven and OCI proxies both stage bytes and then decide not to keep them (a digest
    // mismatch, an upstream that answered with something else). They used to unlink the temp file
    // themselves; the staging is the store's now, so the discard is too.
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(noise(300, 5)), 1 << 20);
    assertEquals(1, stagingCount());

    blobStore.discard(staged);

    assertEquals(0, stagingCount());
    assertEquals(0, chunkCount());
    assertFalse(blobStore.exists(staged.sha256()));
  }

  @Test
  void malformedIdIsNotFoundRatherThanAQuery() {
    // The shape check that used to keep a caller off the fan-out directories now keeps one off the
    // tables. Nothing reaches a statement until the id is 64 lowercase hex characters.
    assertThrows(NotFoundException.class, () -> blobStore.open("../../etc/passwd"));
    assertThrows(NotFoundException.class, () -> blobStore.open("not-a-sha"));
    assertThrows(NotFoundException.class, () -> blobStore.open("A".repeat(64)));
    assertThrows(NotFoundException.class, () -> blobStore.openChannel("' or 1=1 --"));
    assertThrows(NotFoundException.class, () -> blobStore.size(null));
    assertFalse(blobStore.exists("../../etc/passwd"));
    assertNull(blobStore.lastWrittenAt("not-a-sha"));
  }

  @Test
  void unknownButWellShapedIdIsNotFound() {
    assertThrows(NotFoundException.class, () -> blobStore.open("a".repeat(64)));
    assertThrows(NotFoundException.class, () -> blobStore.openChannel("a".repeat(64)));
    assertFalse(blobStore.exists("a".repeat(64)));
  }

  @Test
  void deleteRefusesABlobInsideTheGraceWindow() {
    // The upload race, closed by arithmetic rather than by hope: a blob written moments ago may be
    // the one a manifest is about to name, and the sweep's census cannot see a request in flight.
    String blobId = stored(21);

    assertEquals(BlobStore.DeleteResult.WITHIN_GRACE_WINDOW, blobStore.delete(blobId, id -> true));
    assertTrue(blobStore.exists(blobId), "refused means the bytes are still there");
  }

  @Test
  void deleteRefusesWhenTheRecensusStillFindsAReference() {
    // A plan is a photograph. Between the census it was built from and this removal, a push may
    // have made the blob live again — so the last word is the guard's, under the blob's own lock.
    String blobId = stored(22);
    backdate(blobId, Duration.ofDays(30));

    assertEquals(BlobStore.DeleteResult.STILL_REFERENCED, blobStore.delete(blobId, id -> false));
    assertTrue(blobStore.exists(blobId));
  }

  @Test
  void deleteRemovesAnAgedUnreferencedBlobAndItsChunks() {
    // The one path that removes bytes, and the census must stop counting them the moment it does.
    String blobId = stored(23);
    backdate(blobId, Duration.ofDays(30));
    assertTrue(diskIndex.sizes().containsKey(blobId));

    assertEquals(BlobStore.DeleteResult.DELETED, blobStore.delete(blobId, id -> true));

    assertFalse(blobStore.exists(blobId));
    assertFalse(diskIndex.sizes().containsKey(blobId), "the summary must not still count it");
    assertEquals(0, chunkCount(), "the content went with the blob, and the chunks with the content");
  }

  @Test
  void deleteAnswersRatherThanThrowsForAMissingBlobAndAMalformedId() {
    // Every outcome here is normal. A sweep runs against a store that moved under it, and a
    // primitive that threw on "already gone" would make the ordinary case look like a failure.
    assertEquals(BlobStore.DeleteResult.ALREADY_GONE, blobStore.delete("a".repeat(64), id -> true));
    assertEquals(
        BlobStore.DeleteResult.NOT_A_BLOB_ID, blobStore.delete("../../etc/passwd", id -> true));
  }

  @Test
  void theIndexCountsPromotedBlobsAndNothingElse() {
    // "On disk" means "a promoted blob row" now. Staged content is invisible here exactly as the
    // old temp directory was, which is what keeps the orphan arithmetic in a census honest.
    String blobId = stored(24);
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(noise(200, 6)), 1 << 20);

    assertEquals(1, diskIndex.sizes().size());
    assertEquals(TestMedia.png(2, 2, 24).length, diskIndex.sizes().get(blobId));
    assertFalse(diskIndex.sizes().containsKey(staged.sha256()), "staging is not stored content");

    blobStore.discard(staged);
  }

  @Test
  void twoDistinctBlobsDoNotShareAChunkStream() {
    String first = stored(25);
    String second = stored(26);
    assertNotEquals(first, second);

    assertEquals(TestMedia.png(2, 2, 25).length, blobStore.size(first));
    assertEquals(TestMedia.png(2, 2, 26).length, blobStore.size(second));
  }

  @Test
  void aBlobRecordsTheChunkSizeItWasWrittenAt() {
    // Reads divide by the recorded size, not by the configured one, so a deployment that ever
    // changed the key would still serve everything stored before the change.
    String blobId = stored(27);

    assertEquals(64, count("select chunk_size from blob where id = '" + blobId + "'"));
    assertEquals(
        1 << 20,
        BlobStore.DEFAULT_CHUNK_SIZE,
        "the shipped default is 1 MiB; only this suite runs it smaller");
  }

  private String stored(int seed) {
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(TestMedia.png(2, 2, seed)), 1024);
    blobStore.promote(staged);
    return staged.sha256();
  }

  /** Repeatable pseudo-random bytes: unique content of any length, without a fixture file. */
  static byte[] noise(int length, int seed) {
    byte[] bytes = new byte[length];
    new java.util.Random(seed).nextBytes(bytes);
    return bytes;
  }

  static String sha256(byte[] bytes) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}
