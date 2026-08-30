package eu.wohlben.qits.artifacts.control;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.UUID;

/**
 * Random access to a stored blob, by arithmetic on chunk numbers: {@code seq = position /
 * chunk_size}.
 *
 * <p>This is what JGit needs and a stream cannot give — a clone is served out of one large pack by
 * seeking around it. Each miss is one autocommit {@code SELECT}; the last chunk read is kept, which
 * is what makes a run of block reads inside one chunk free (JGit's block size is at most 64 KiB
 * against a 1 MiB chunk).
 *
 * <p><b>Nothing is held between calls.</b> No connection, no transaction, no lock — so JGit may sit
 * on an open channel for as long as it likes. That is why {@link #close} is bookkeeping only, and
 * why the class is safe against a stalled client in a way a large-object handle would not be.
 *
 * <p><b>Read-only.</b> {@link #write} and {@link #truncate} throw: a stored blob is addressed by its
 * content, so a channel that could change it would be a lie. Writers use {@link ScratchBlob}.
 *
 * <p>Not thread-safe — one channel, one reader, the same contract {@code FileChannel} users here
 * already keep.
 */
final class BlobChunkChannel implements SeekableByteChannel {

  private final BlobDb db;
  private final UUID contentId;
  private final long size;
  private final int chunkSize;

  private long position;
  private int cachedSeq = -1;
  private byte[] cached;
  private boolean open = true;

  BlobChunkChannel(BlobDb db, UUID contentId, long size, int chunkSize) {
    this.db = db;
    this.contentId = contentId;
    this.size = size;
    this.chunkSize = chunkSize;
  }

  @Override
  public int read(ByteBuffer destination) throws ClosedChannelException {
    requireOpen();
    if (position >= size) {
      return -1;
    }
    int total = 0;
    // Fills across chunk boundaries rather than stopping at one: a caller asking for 64 KiB that
    // straddles a boundary gets 64 KiB, and callers that do not loop stay correct.
    while (destination.hasRemaining() && position < size) {
      byte[] chunk = chunkAt((int) (position / chunkSize));
      if (chunk == null) {
        break;
      }
      int offset = (int) (position % chunkSize);
      if (offset >= chunk.length) {
        break;
      }
      int taken = Math.min(destination.remaining(), chunk.length - offset);
      destination.put(chunk, offset, taken);
      position += taken;
      total += taken;
    }
    return total == 0 ? -1 : total;
  }

  private byte[] chunkAt(int seq) {
    if (seq == cachedSeq) {
      return cached;
    }
    byte[] chunk = BlobChunks.read(db, contentId, seq);
    if (chunk != null) {
      cachedSeq = seq;
      cached = chunk;
    }
    return chunk;
  }

  @Override
  public int write(ByteBuffer source) {
    throw new NonWritableChannelException();
  }

  @Override
  public SeekableByteChannel truncate(long newSize) {
    throw new NonWritableChannelException();
  }

  @Override
  public long position() throws ClosedChannelException {
    requireOpen();
    return position;
  }

  @Override
  public SeekableByteChannel position(long newPosition) throws ClosedChannelException {
    requireOpen();
    if (newPosition < 0) {
      throw new IllegalArgumentException("negative position: " + newPosition);
    }
    position = newPosition;
    return this;
  }

  @Override
  public long size() throws ClosedChannelException {
    requireOpen();
    return size;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
    cached = null;
    cachedSeq = -1;
  }

  private void requireOpen() throws ClosedChannelException {
    if (!open) {
      throw new ClosedChannelException();
    }
  }
}
