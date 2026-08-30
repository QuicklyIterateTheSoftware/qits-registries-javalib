package eu.wohlben.qits.artifacts.control;

import java.util.UUID;

/**
 * The write end of a staging area: bytes in, whole chunks out to {@code blob_chunk}.
 *
 * <p>One buffer of exactly one chunk. A caller appends as little or as much as it likes; a chunk
 * row is written the moment the buffer fills, so a gigabyte push holds one chunk in memory rather
 * than a gigabyte, and the partial remainder is written by {@link #finish}.
 *
 * <p><b>Not thread-safe</b>, like the stages built on it: one upload, one writer.
 */
final class StagingChunks {

  private final BlobDb db;
  private final UUID contentId;
  private final int chunkSize;
  private final byte[] buffer;

  private int buffered;
  private int nextSeq;
  private long written;
  private long flushed;

  StagingChunks(BlobDb db, UUID contentId, int chunkSize) {
    this.db = db;
    this.contentId = contentId;
    this.chunkSize = chunkSize;
    this.buffer = new byte[chunkSize];
  }

  UUID contentId() {
    return contentId;
  }

  int chunkSize() {
    return chunkSize;
  }

  /** Everything appended so far, flushed or not. */
  long size() {
    return written;
  }

  /**
   * How many bytes are already chunk rows. Below this, a read must go to the database.
   *
   * <p>Counted rather than derived from the chunk number: the last flush writes a short chunk, so
   * {@code nextSeq * chunkSize} stops being the truth the moment {@link #finish} runs.
   */
  long flushed() {
    return flushed;
  }

  void append(byte[] src, int off, int len) {
    int remaining = len;
    int from = off;
    while (remaining > 0) {
      int taken = Math.min(remaining, chunkSize - buffered);
      System.arraycopy(src, from, buffer, buffered, taken);
      buffered += taken;
      written += taken;
      from += taken;
      remaining -= taken;
      if (buffered == chunkSize) {
        flush();
      }
    }
  }

  /** Writes the partial remainder as this content's last chunk. Idempotent. */
  void finish() {
    if (buffered > 0) {
      flush();
    }
  }

  /**
   * The live buffer holding the unflushed tail — read {@link #buffered} bytes of it, and only from
   * this package. Handed out rather than copied because {@link ScratchBlob} reads the tail on every
   * positional read of a growing pack, and a copy per read would be a copy of a megabyte.
   */
  byte[] buffer() {
    return buffer;
  }

  /** How many bytes of {@link #buffer} are the unflushed tail. */
  int buffered() {
    return buffered;
  }

  private void flush() {
    BlobChunks.insert(db, contentId, nextSeq, buffer, buffered);
    nextSeq++;
    flushed += buffered;
    buffered = 0;
  }
}
