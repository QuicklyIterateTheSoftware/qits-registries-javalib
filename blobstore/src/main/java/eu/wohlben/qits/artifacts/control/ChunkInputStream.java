package eu.wohlben.qits.artifacts.control;

import java.io.InputStream;
import java.util.function.IntFunction;

/**
 * A forward stream over numbered chunks. The supplier answers one chunk per call and {@code null}
 * past the end, so this class holds one chunk at a time and no connection at all between calls —
 * which is the point: a slow client reading a 1 GiB layer parks on the socket, not on the pool.
 */
final class ChunkInputStream extends InputStream {

  private final IntFunction<byte[]> chunks;

  private byte[] current = new byte[0];
  private int position;
  private int seq;
  private boolean ended;

  ChunkInputStream(IntFunction<byte[]> chunks) {
    this.chunks = chunks;
  }

  @Override
  public int read() {
    if (!advance()) {
      return -1;
    }
    return current[position++] & 0xFF;
  }

  @Override
  public int read(byte[] destination, int off, int len) {
    if (len == 0) {
      return 0;
    }
    if (!advance()) {
      return -1;
    }
    int taken = Math.min(len, current.length - position);
    System.arraycopy(current, position, destination, off, taken);
    position += taken;
    return taken;
  }

  @Override
  public int available() {
    return current.length - position;
  }

  /** True once a byte is ready in {@link #current}; false at the end of the content. */
  private boolean advance() {
    while (position == current.length) {
      if (ended) {
        return false;
      }
      byte[] next = chunks.apply(seq++);
      if (next == null) {
        ended = true;
        return false;
      }
      current = next;
      position = 0;
    }
    return true;
  }
}
