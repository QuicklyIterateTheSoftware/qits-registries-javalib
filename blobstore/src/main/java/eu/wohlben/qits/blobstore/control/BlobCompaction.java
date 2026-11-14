package eu.wohlben.qits.blobstore.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The blob store's disk-return door, opened exactly wide enough for a garbage collector.
 *
 * <p><b>Why this exists beside {@link BlobReclaim}.</b> Reclaiming a blob deletes its rows, and that
 * is all it does: PostgreSQL keeps the space the rows occupied inside the table's files and never
 * gives it back to the filesystem. A sweep that removed 80% of a store therefore leaves the volume
 * exactly as full as it was. This door is the second half — it turns space that deletes made
 * reusable into space the disk gets back, by draining mostly-dead chunk segments and dropping their
 * partitions. See {@link BlobSegments} for the mechanism and the invariants that make it safe
 * against concurrent reads, uploads and deletes.
 *
 * <p><b>The owner is a consuming service's garbage collector and nothing else</b> — the same sweep
 * that calls {@link BlobReclaim#delete}, calling {@link #compact} once after its deletions. A second
 * caller appearing anywhere is the signal that this door has become an API, which it is not.
 *
 * <p><b>Not inside a transaction.</b> Every step commits on its own — that is what keeps each step
 * short — so a call from inside {@code @Transactional} is refused with an {@link
 * IllegalStateException}. Call it from the sweep's plain, non-transactional body.
 *
 * <p><b>Safe before a consumer migrates.</b> On the pre-segment schema (no {@code blob_segment}
 * table, see {@code db/blobstore-segments-upgrade.sql}) a run changes nothing and answers {@link
 * CompactionResult.State#UNSEGMENTED}, with the disk figures still filled in. A sweep can call this
 * from the release that bumps the library, before the release that migrates.
 */
@ApplicationScoped
public class BlobCompaction {

  @Inject BlobSegments segments;

  /**
   * What one {@link #compact} run did, and what it was worth on disk.
   *
   * <p><b>Two disk figures, on purpose.</b> {@code blobTablesBytes*} is the blob tables' own
   * on-disk size — every partition of {@code blob_chunk} with its TOAST and index, plus {@code
   * blob}, {@code blob_content} and {@code blob_segment} — and is what this run is accountable for.
   * {@code databaseBytes*} is {@code pg_database_size} of the whole database, which is what the
   * volume sees and what other tables move too. Both are measured, not estimated: a dropped
   * partition's files are unlinked (or truncated to nothing until the next checkpoint) when the
   * drop commits, so "after" is the disk as it stands when the run ends.
   *
   * <p>Neither figure is the size of the blobs a sweep deleted. A delete frees no disk, and a sweep
   * that reports its deleted bytes as disk returned is reporting a number the volume never sees.
   *
   * @param state whether the run did anything, and why not if it did not
   * @param blobTablesBytesBefore the blob tables' on-disk bytes when the run started
   * @param blobTablesBytesAfter the blob tables' on-disk bytes when the run ended
   * @param databaseBytesBefore {@code pg_database_size(current_database())} when the run started
   * @param databaseBytesAfter {@code pg_database_size(current_database())} when the run ended
   * @param segmentsDrained closed segments whose live share was below {@code
   *     qits.artifacts.blobs.compaction.live-ratio} and whose promoted chunks were moved out
   * @param segmentsDropped partitions dropped, their files given back — drained ones and ones that
   *     were already empty
   * @param segmentsHeld segments that were drained or empty but could not be dropped this run:
   *     STAGING chunks of an upload still in them, or the parent lock not granted within its
   *     timeout. Not a failure; the next run tries again
   * @param bytesMoved chunk bytes copied into the open segment while draining — the price paid in
   *     writes (and WAL) for the disk returned
   */
  public record CompactionResult(
      State state,
      long blobTablesBytesBefore,
      long blobTablesBytesAfter,
      long databaseBytesBefore,
      long databaseBytesAfter,
      int segmentsDrained,
      int segmentsDropped,
      int segmentsHeld,
      long bytesMoved) {

    /** Why a run did what it did. Every value is a normal answer, not an error. */
    public enum State {
      /** The run looked at every closed segment, and drained and dropped what qualified. */
      COMPACTED,
      /**
       * The blob tables are the pre-segment shape: the consumer has not run {@code
       * db/blobstore-segments-upgrade.sql}. Nothing was changed; the disk figures are measured.
       */
      UNSEGMENTED,
      /** Another process — a replica's sweep — holds the compaction lock. Nothing was changed. */
      ALREADY_RUNNING
    }

    /** On-disk bytes the blob tables gave back in this run. Negative if they grew meanwhile. */
    public long blobTablesBytesReturned() {
      return blobTablesBytesBefore - blobTablesBytesAfter;
    }

    /** On-disk bytes the whole database gave back in this run. Other tables move it too. */
    public long databaseBytesReturned() {
      return databaseBytesBefore - databaseBytesAfter;
    }
  }

  /**
   * Returns the disk that deleted blobs left behind, as far as it is worth moving bytes for.
   *
   * <ol>
   *   <li>Measures the blob tables and the database.
   *   <li>Takes every <em>closed</em> segment — every one older than the segment open when the run
   *       started — oldest first, and computes its live share: the stored size of its chunk values
   *       over the partition's on-disk size.
   *   <li>A segment below {@code qits.artifacts.blobs.compaction.live-ratio} (default 0.5) is
   *       drained: each promoted blob's chunks are moved into the open segment, under that blob's
   *       advisory lock, a batch per short transaction. STAGING content is never moved.
   *   <li>A segment that holds no rows — drained, or emptied by deletes alone — is dropped, the
   *       emptiness proved in the transaction that drops it.
   *   <li>Measures again and returns the figures. Logged at INFO.
   * </ol>
   *
   * <p>Readers never miss a chunk and never see one twice; uploads and promotes carry on throughout.
   * The only waits anyone sees are one blob's advisory lock while its batch moves, and the parent
   * lock for the instant each drop takes (bounded: a drop that cannot get it in two seconds is left
   * for the next run).
   *
   * @throws IllegalStateException if called inside a caller's transaction
   */
  public CompactionResult compact() {
    return segments.compact();
  }
}
