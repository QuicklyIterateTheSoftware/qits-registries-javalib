package eu.wohlben.qits.artifacts.control;

import java.io.Closeable;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * A staging area a writer can <b>read back</b> while it fills it.
 *
 * <p>{@code BlobStore.IncrementalStage} cannot serve every writer, and this is the gap: it wraps an
 * output stream over a running digest and offers no way to see a byte again. JGit's pack parser
 * reads deltas back out of a pack it has not finished storing, and the docs bundle unpacks a tar it
 * is still staging. Both need this.
 *
 * <p>Bytes go straight into {@code blob_chunk} rows under a {@code STAGING} content id: full chunks
 * as they fill, the remainder when the writer finishes. A {@link #read} below the flushed watermark
 * comes from the database, above it from the one buffered chunk in memory — so a pack far larger
 * than the heap is readable throughout.
 *
 * <p><b>Ownership.</b> The writer either hands {@link #contentId} to {@code BlobStore.promote} in a
 * {@code StagedBlob}, or it does not; {@link #close} discards whatever is still staged, so a
 * writer that abandons its blob leaves nothing behind and a writer that promoted may still close.
 * Close exactly once, always — including after promoting.
 *
 * <p>Not thread-safe: one blob, one writer.
 */
public interface ScratchBlob extends Closeable {

  /** The staging content id, for the {@code StagedBlob} handed to {@code BlobStore.promote}. */
  UUID contentId();

  /** Appends {@code len} bytes at the current end. */
  void write(byte[] buf, int off, int len);

  /**
   * Reads up to {@code dst.remaining()} bytes from {@code position} of what has been written so
   * far, without moving the append point.
   *
   * @return the number of bytes read, or {@code -1} past the end
   */
  int read(long position, ByteBuffer dst);

  /**
   * Seals the blob and returns a forward stream over all of it — how a writer hashes what it
   * staged, and therefore the last step before {@code promote}.
   *
   * <p><b>Sealing is what writes the final short chunk</b>, so it has to happen exactly once and at
   * the end: a short chunk in the middle would break the {@code seq = position / chunk_size}
   * arithmetic every read depends on. {@link #write} after this throws. Reopening the stream is
   * fine.
   *
   * <p>Promote a blob only after sealing it, or its last partial chunk is not in the database. In
   * practice that is automatic — the content address {@code promote} needs comes from reading the
   * content back, which is this method.
   */
  InputStream openRead();

  /** Bytes written so far. */
  long size();

  /** Discards the staging unless {@code promote} adopted this content id. Safe to call twice. */
  @Override
  void close();
}
