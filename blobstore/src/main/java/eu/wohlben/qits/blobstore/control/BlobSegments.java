package eu.wohlben.qits.blobstore.control;

import eu.wohlben.qits.blobstore.control.BlobCompaction.CompactionResult;
import eu.wohlben.qits.blobstore.error.InternalServerErrorException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Where chunk bytes are written, and how the disk they occupied is given back.
 *
 * <p><b>The problem this exists for.</b> PostgreSQL never returns the space a delete frees to the
 * filesystem. {@link BlobStore#delete} removes a blob's rows and the space becomes reusable inside
 * the chunk table's files, but the files stay at their peak — a registry that once held 300 GiB and
 * now holds 40 still occupies 300 on disk. The two stock remedies both rewrite the whole table:
 * {@code VACUUM FULL} under an ACCESS EXCLUSIVE lock for as long as the copy takes, {@code pg_repack}
 * without the lock but with free disk equal to the live data. On the volume a full blob store lives
 * on, neither is available when it is needed.
 *
 * <p><b>The way out is a file that can be dropped whole.</b> {@code blob_chunk} is list-partitioned
 * by a {@code segment} number (see {@code db/blobstore-tables.sql}). Every chunk is written into one
 * <em>open</em> segment, named by the single row of {@code blob_segment}; once that partition's
 * on-disk size passes {@code qits.artifacts.blobs.segment-size}, the next promote <b>rolls</b> to a
 * fresh partition. Closed segments only ever lose rows, to deletes. {@link #compact} — reached
 * through {@link BlobCompaction} — finds the closed segments that are mostly dead, moves their few
 * live chunks into the open segment one blob at a time, and drops the emptied partition, which
 * unlinks its files. The disk returned is a whole segment minus its live share, and no statement
 * ever touches more than one blob's chunks or holds a lock wider than a moment. A closed segment
 * past twice the segment size — segment 0, the whole table the upgrade attached — is instead shrunk
 * from its tail a bounded step per run, see {@link #shrinkTail}.
 *
 * <p><b>The invariants that make it safe</b>, each restated where it is enforced:
 *
 * <ul>
 *   <li><b>A chunk insert names its segment in the statement that writes it</b> — {@code
 *       (select segment from blob_segment)} in the {@code VALUES} — so there is no window between
 *       choosing a segment and writing into it beyond one statement's own snapshot. A writer that
 *       names none gets the same answer from the column default, {@code blob_open_segment()}. A
 *       roll blocks every writer on the parent while it attaches the next partition, so a statement
 *       that reads the new number always routes against a partition list that contains it.
 *   <li><b>A compaction run never drains the segment that was open when it started</b>, nor any
 *       later one. A writer whose statement read the old open number before a roll committed is
 *       therefore always writing into a segment this run leaves alone.
 *   <li><b>A segment is dropped only after it is proved empty under an ACCESS EXCLUSIVE lock on the
 *       parent</b>, in the transaction that drops it. Every chunk insert and read goes through the
 *       parent and holds a lock on it while it runs, so none can be half-way into the partition
 *       being dropped; one that queued behind the lock re-plans against the partitions that remain.
 *   <li><b>A drained blob moves under its own advisory lock</b> — the one {@link BlobStore#promote}
 *       and {@link BlobStore#delete} take — and each batch of its chunks moves in one transaction:
 *       the copy into the open segment and the delete from the old one commit together, so a
 *       reader's single-statement snapshot sees every chunk exactly once.
 *   <li><b>STAGING content is never moved.</b> It may still be growing and it belongs to an upload,
 *       not to the store; its segment simply survives until the upload promotes (and the next run
 *       drains it) or the staging sweep reaps it (and the next run drops it empty).
 * </ul>
 *
 * <p><b>Backward compatible by detection.</b> A consumer that has not run {@code
 * db/blobstore-segments-upgrade.sql} has no {@code blob_segment} table and a plain {@code
 * blob_chunk} without the column. That is detected once, lazily, and while it holds the store
 * writes exactly as it always did: inserts omit the segment, a roll is a no-op, and compaction
 * answers {@link CompactionResult.State#UNSEGMENTED}. Detection is repeated at the head of every
 * compaction run and whenever a chunk insert fails the way a schema change under a running process
 * makes it fail, so a consumer that migrates while replicas are up is picked up without a restart.
 */
@ApplicationScoped
class BlobSegments {

  private static final Logger LOG = Logger.getLogger(BlobSegments.class);

  /** What every partition is named, by segment number. The roll creates names in this shape. */
  static final String PARTITION_PREFIX = "blob_chunk_";

  /**
   * How long the roll and the drop wait for a table lock before giving up for this time round.
   *
   * <p>Both queue an exclusive-ish lock on a table every blob read passes through, and PostgreSQL
   * queues every later request behind a waiting one — so a drop stuck behind a long statement
   * (a {@code pg_dump}, say) would stall every blob read for as long as it waited. Two seconds is
   * far longer than any chunk statement and far shorter than anyone notices; giving up costs
   * nothing, because the next promote rolls and the next compaction run drops.
   */
  static final String LOCK_TIMEOUT = "2s";

  /**
   * Chunks moved per transaction while draining one blob. 64 MiB at the production chunk size: each
   * batch holds that blob's advisory lock (a concurrent promote of the same bytes, or a delete of
   * that blob, waits on it), so a 5 GiB layer moves in eighty short transactions rather than one long
   * one. Per-batch atomicity is all a reader needs — every chunk is its own single-statement read.
   */
  static final int DRAIN_BATCH = 64;

  /** After a roll fails, how long promotes leave it alone before trying again — no log storm. */
  private static final long ROLL_RETRY_BACKOFF_NANOS = 60_000_000_000L;

  private static final String ROLL_LOCK = "qits.blobstore.segment-roll";

  private static final String COMPACTION_LOCK = "qits.blobstore.segment-compaction";

  private static final Pattern LIST_BOUND = Pattern.compile("FOR VALUES IN \\((-?\\d+)\\)");

  /** SQLSTATE 23502: a null where the table forbids one — here, a chunk inserted without segment. */
  private static final String NOT_NULL_VIOLATION = "23502";

  /** SQLSTATE 23514: what PostgreSQL raises for "no partition of relation found for row". */
  private static final String CHECK_VIOLATION = "23514";

  /** SQLSTATE 42P01 / 42703: a table or column a statement names is not there (schema moved). */
  private static final String UNDEFINED_TABLE = "42P01";

  private static final String UNDEFINED_COLUMN = "42703";

  @Inject BlobDb db;

  /**
   * The on-disk size, in bytes, past which the open segment is closed and a new one opened.
   * Measured as {@code pg_total_relation_size} of the open partition — heap, TOAST and key index,
   * i.e. what dropping it would give back.
   *
   * <p><b>A threshold, not a cap.</b> The check runs after each promote, so a segment overshoots by
   * whatever the blob that crossed it held, and by every chunk staged meanwhile. The size trades
   * two costs: smaller segments return disk in finer steps and leave less dead space in the
   * segment that is still open, but every chunk read probes every partition's key index, so the
   * count of partitions is a read cost. At 1 GiB, a store of 200 GiB live is ~200 partitions after
   * compaction.
   */
  @ConfigProperty(name = "qits.artifacts.blobs.segment-size", defaultValue = "1073741824")
  long segmentSize;

  /**
   * The live share below which compaction drains a closed segment: the summed stored size of its
   * chunk values over the partition's on-disk size. At 0.5 a drained segment costs at most one
   * byte moved per byte returned, and a segment that is still mostly live is left to fall further.
   */
  @ConfigProperty(name = "qits.artifacts.blobs.compaction.live-ratio", defaultValue = "0.5")
  double liveRatio;

  /**
   * How far one compaction run shrinks oversized closed segments from their tail, in bytes of the
   * TOAST relation where chunk bytes live. An oversized segment is one past twice {@code
   * segment-size} — in practice segment 0, the whole pre-qits-2248 table the upgrade attached.
   *
   * <p>The budget bounds what one run costs: at most this many bytes read in the tail, roughly this
   * many moved into the open segment (one chunk past it, for a value straddling the tail's edge) and
   * so roughly this much free disk needed at once, before the truncation gives it back. See {@link
   * #shrinkTail}.
   */
  @ConfigProperty(name = "qits.artifacts.blobs.compaction.shrink-budget", defaultValue = "1073741824")
  long shrinkBudget;

  /** Null until first asked; then whether {@code blob_segment} exists. See the class comment. */
  private volatile Boolean segmented;

  private volatile long rollRetryAfterNanos = System.nanoTime();

  /** Whether this database carries the segmented schema. Detected once, then cached. */
  boolean segmented() {
    Boolean known = segmented;
    if (known != null) {
      return known;
    }
    boolean found =
        db.autocommit(
            "Failed to detect the blob segment schema",
            connection -> {
              try (Statement select = connection.createStatement();
                  ResultSet row = select.executeQuery("select to_regclass('blob_segment') is not null")) {
                row.next();
                return row.getBoolean(1);
              }
            });
    segmented = found;
    return found;
  }

  /** Forgets the cached detection, so the next {@link #segmented} asks the database again. */
  void redetect() {
    segmented = null;
  }

  /**
   * Appends one chunk row, into the open segment if there are segments.
   *
   * <p>One retry, for one reason: the schema changed under this process. A replica that detected
   * the old schema keeps omitting the segment after a consumer migrates, and the first such insert
   * fails — no partition takes a NULL segment — which is the cue to look again and write it the new
   * way. The opposite case, a segmented insert that finds no partition for the segment it read,
   * cannot happen while a roll blocks writers on the parent (see {@link #roll(int)}) and a drop
   * holds the parent lock (see {@link #compact}), and is retried all the same because a failed
   * insert wrote nothing and a second one, in a fresh statement, sees the partitions afresh.
   */
  void insertChunk(UUID contentId, int seq, byte[] bytes) {
    boolean asSegmented = segmented();
    try {
      insertOnce(contentId, seq, bytes, asSegmented);
    } catch (InternalServerErrorException e) {
      if (!schemaMovedUnder(e, asSegmented)) {
        throw e;
      }
      redetect();
      LOG.infof(
          "A blob chunk insert met a %s schema; detected again and retrying",
          asSegmented ? "changed segment" : "segmented");
      insertOnce(contentId, seq, bytes, segmented());
    }
  }

  private void insertOnce(UUID contentId, int seq, byte[] bytes, boolean asSegmented) {
    db.autocommit(
        "Failed to store a blob chunk",
        connection -> {
          try (PreparedStatement insert =
              connection.prepareStatement(
                  asSegmented
                      // A scalar subquery in VALUES, not INSERT ... SELECT: an empty blob_segment
                      // must fail on the NOT NULL rather than insert zero rows and lose a chunk.
                      ? "insert into blob_chunk (content_id, seq, bytes, segment)"
                          + " values (?, ?, ?, (select segment from blob_segment))"
                      : "insert into blob_chunk (content_id, seq, bytes) values (?, ?, ?)")) {
            insert.setObject(1, contentId);
            insert.setInt(2, seq);
            insert.setBytes(3, bytes);
            insert.executeUpdate();
          }
          return null;
        });
  }

  private static boolean schemaMovedUnder(RuntimeException e, boolean asSegmented) {
    if (!(e.getCause() instanceof SQLException sql)) {
      return false;
    }
    String state = sql.getSQLState();
    // Unsegmented, against a table that is partitioned now: the missing segment is NULL, and the
    // routing that finds no partition for NULL fails before the NOT NULL check would — so either
    // state can be the one that arrives.
    return asSegmented
        ? CHECK_VIOLATION.equals(state)
            || UNDEFINED_TABLE.equals(state)
            || UNDEFINED_COLUMN.equals(state)
        : NOT_NULL_VIOLATION.equals(state) || CHECK_VIOLATION.equals(state);
  }

  /**
   * Rolls to a fresh segment if the open one has passed {@code qits.artifacts.blobs.segment-size}.
   * Called after every promote that stored new content, outside promote's transaction.
   *
   * <p><b>Never throws.</b> A blob that was promoted is stored, whatever happens here: a roll that
   * fails is logged and tried again by a later promote (after a minute's backoff), and the only cost
   * of a late roll is a larger segment. Inside a caller's transaction it does nothing at all — DDL
   * and a parent lock held for a caller's lifetime are worse than a late roll.
   */
  void rollIfFull() {
    if (System.nanoTime() - rollRetryAfterNanos < 0) {
      return;
    }
    try {
      if (!segmented()) {
        return;
      }
      OpenSegment open = openSegment();
      if (open.diskBytes() < segmentSize) {
        return;
      }
      if (db.callerTransactionActive()) {
        return;
      }
      roll(open.segment());
    } catch (RuntimeException e) {
      rollRetryAfterNanos = System.nanoTime() + ROLL_RETRY_BACKOFF_NANOS;
      LOG.warn("Could not roll the open blob segment; the next promote after a minute tries again", e);
    }
  }

  /** The open segment and its partition's on-disk size. */
  record OpenSegment(int segment, long diskBytes) {}

  OpenSegment openSegment() {
    return db.autocommit(
        "Failed to read the open blob segment",
        connection -> {
          try (Statement select = connection.createStatement();
              ResultSet row =
                  select.executeQuery(
                      "select segment, coalesce(pg_total_relation_size(to_regclass('"
                          + PARTITION_PREFIX
                          + "' || segment)), 0) from blob_segment")) {
            if (!row.next()) {
              throw new SQLException("blob_segment holds no row");
            }
            return new OpenSegment(row.getInt(1), row.getLong(2));
          }
        });
  }

  /**
   * Opens segment {@code from + 1}: creates its partition, attaches it, and moves {@code
   * blob_segment} to it, in one transaction.
   *
   * <p><b>Create-then-attach, not {@code CREATE TABLE ... PARTITION OF}.</b> The latter takes an
   * ACCESS EXCLUSIVE lock on the parent, which would stall every blob read for the moment it runs;
   * ATTACH takes only SHARE UPDATE EXCLUSIVE there, which reads pass straight through. {@code LIKE
   * ... INCLUDING ALL} copies the columns, the {@code blob_open_segment()} default, the external
   * storage of {@code bytes} and the primary key, so ATTACH adopts that key as the partition's half
   * of the parent's and clones the foreign key onto an empty table — nothing to validate.
   *
   * <p><b>But the weak lock opens a race, and the roll closes it by pausing writers.</b> Because
   * ATTACH does not conflict with a writer's lock on the parent, a chunk insert can take that lock —
   * and with it the parent's partition list — before the roll commits, then read the NEW open
   * segment after, and find no partition for it in the list it holds ("no partition of relation
   * found for row"). Measured: nine such inserts in forty runs of the concurrency suite. Locking
   * {@code blob_segment} closes it for a statement that reads that table while it is planned, but
   * only incidentally for a writer that leaves the segment to the column default — a library older
   * than qits-2248, mid-rollout — which evaluates {@code blob_open_segment()} during execution:
   * whether its partition list is loaded before or after that is an executor detail (PostgreSQL 18
   * loads it after, measured; nothing promises it). So the roll takes, before the attach and held
   * to the commit:
   *
   * <ol>
   *   <li>SHARE ROW EXCLUSIVE on {@code blob_content} — what the attach's foreign-key clone needs
   *       anyway, taken first so that a delete holding {@code blob_content} and cascading into the
   *       parent can never wait on the roll while the roll waits on it;
   *   <li>SHARE ROW EXCLUSIVE on the parent ({@code ONLY}, not the partitions). It conflicts with
   *       every writer's ROW EXCLUSIVE and with no reader's ACCESS SHARE: chunk inserts, drains and
   *       cascading deletes queue behind the roll, chunk reads do not notice it. A writer granted the
   *       lock after waiting processes the invalidations the roll's commit sent, so the partition list
   *       it routes against already holds the new partition, and its snapshot already sees the new
   *       open segment;
   *   <li>ACCESS EXCLUSIVE on {@code blob_segment}, so nothing reads the open number mid-roll.
   * </ol>
   *
   * Writers pause for the few milliseconds a roll takes, once per segment-size of writes; reads never.
   *
   * <p>Under a store-wide advisory lock, and only if {@code blob_segment} still says {@code from}:
   * every replica's promotes see the same full segment, and exactly one of them rolls.
   *
   * @return whether this call rolled; false if another process got there first
   */
  boolean roll(int from) {
    return roll(from, () -> {});
  }

  /**
   * {@link #roll(int)}, running {@code beforeCommit} while every lock is held and the new partition
   * is attached but not yet committed. Here for the suite: it is how a test puts a writer in the
   * queue behind a real roll instead of hoping one lands there.
   */
  boolean roll(int from, Runnable beforeCommit) {
    int next = from + 1;
    String partition = PARTITION_PREFIX + next;
    boolean rolled =
        db.inOwnTransaction(
            "Failed to roll the open blob segment",
            connection -> {
              try (Statement sql = connection.createStatement()) {
                sql.execute("set local lock_timeout = '" + LOCK_TIMEOUT + "'");
                lockNamed(connection, ROLL_LOCK);
                // Before the attach, held to the commit, in this order: see the method comment for
                // why every writer must queue behind the whole roll, and why readers need not.
                sql.execute("lock table blob_content in share row exclusive mode");
                sql.execute("lock table only blob_chunk in share row exclusive mode");
                sql.execute("lock table blob_segment in access exclusive mode");
                try (ResultSet row = sql.executeQuery("select segment from blob_segment")) {
                  if (!row.next() || row.getInt(1) != from) {
                    return false;
                  }
                }
                sql.execute("create table " + partition + " (like blob_chunk including all)");
                sql.execute(
                    "alter table blob_chunk attach partition "
                        + partition
                        + " for values in ("
                        + next
                        + ")");
                sql.execute("update blob_segment set segment = " + next);
                beforeCommit.run();
                return true;
              }
            });
    if (rolled) {
      LOG.infof("Closed blob segment %d and opened %d", from, next);
    }
    return rolled;
  }

  /**
   * The compaction run {@link BlobCompaction#compact} documents. Package-private for the same
   * reason {@link BlobStore#delete} is: the door is the public surface, this is the funnel.
   */
  CompactionResult compact() {
    if (db.callerTransactionActive()) {
      throw new IllegalStateException(
          "Blob compaction must not run inside a transaction: it commits every step on its own");
    }
    redetect();
    Sizes before = measure();
    if (!segmented()) {
      LOG.info("Blob compaction: the blob tables are not segmented yet; nothing to do");
      return new CompactionResult(
          CompactionResult.State.UNSEGMENTED,
          before.tables(), before.tables(), before.database(), before.database(), 0, 0, 0, 0, 0, 0);
    }
    // One run at a time across every replica, on a session lock held by a connection of its own
    // for the length of the run. Two runs would not corrupt anything — every move is per blob under
    // its lock, and the drop proves emptiness — but they would race each other's drains for nothing.
    return db.autocommit(
        "Failed to run blob compaction",
        lockHolder -> {
          if (!tryLockSession(lockHolder)) {
            LOG.info("Blob compaction: another run holds the compaction lock; skipping this one");
            return new CompactionResult(
                CompactionResult.State.ALREADY_RUNNING,
                before.tables(),
                before.tables(),
                before.database(),
                before.database(),
                0, 0, 0, 0, 0, 0);
          }
          try {
            return compactLocked(before);
          } finally {
            unlockSession(lockHolder);
          }
        });
  }

  private CompactionResult compactLocked(Sizes before) {
    // The segment open NOW bounds the run: it and every later one are left alone, which is what
    // makes a writer that read an older open number safe (see the class comment).
    int openAtStart = openSegment().segment();
    int drained = 0;
    int dropped = 0;
    int held = 0;
    int shrunk = 0;
    long moved = 0;
    long truncated = 0;
    long shrinkLeft = shrinkBudget;
    // Asked once per run, at the first oversized segment, and only then: see toastAccess.
    ToastAccess toastAccess = null;
    for (Partition closed : closedPartitions(openAtStart)) {
      Live live = liveShare(closed.segment());
      boolean empty = live.rows() == 0;
      if (!empty && closed.diskBytes() > 2 * segmentSize) {
        // Oversized: never drained whole, whatever its live share — see shrinkTail.
        if (shrinkLeft <= 0) {
          continue;
        }
        if (toastAccess == null) {
          toastAccess = toastAccess();
          if (!toastAccess.usage()) {
            LOG.infof(
                "Blob compaction: segment %d is oversized (%d bytes) but this role cannot read"
                    + " pg_toast, so it is not shrunk; grant usage on schema pg_toast to %s in this"
                    + " database",
                closed.segment(),
                closed.diskBytes(),
                toastAccess.role());
          }
        }
        if (!toastAccess.usage()) {
          continue;
        }
        Shrink shrink = shrinkTail(closed, shrinkLeft);
        shrinkLeft -= shrink.windowBytes();
        moved += shrink.moved();
        truncated += shrink.truncated();
        if (shrink.truncated() > 0) {
          shrunk++;
        }
        if (liveShare(closed.segment()).rows() > 0) {
          continue;
        }
        empty = true;
      }
      double share = closed.diskBytes() == 0 ? 0 : (double) live.bytes() / closed.diskBytes();
      if (!empty && share >= liveRatio) {
        continue;
      }
      if (!empty) {
        long movedHere = drain(closed.segment());
        if (movedHere > 0) {
          moved += movedHere;
          drained++;
          // The drained bytes landed in the open segment, which may now be past its size.
          rollIfFull();
        }
      }
      if (dropIfEmpty(closed)) {
        dropped++;
      } else {
        held++;
      }
    }
    Sizes after = measure();
    CompactionResult result =
        new CompactionResult(
            CompactionResult.State.COMPACTED,
            before.tables(),
            after.tables(),
            before.database(),
            after.database(),
            drained,
            dropped,
            held,
            moved,
            shrunk,
            truncated);
    LOG.infof(
        "Blob compaction: drained %d segments, shrunk %d oversized ones by %d bytes (%d bytes moved"
            + " in all), dropped %d, held %d; blob tables %d -> %d bytes on disk, database %d -> %d"
            + " bytes",
        drained,
        shrunk,
        truncated,
        moved,
        dropped,
        held,
        before.tables(),
        after.tables(),
        before.database(),
        after.database());
    return result;
  }

  /**
   * What one tail shrink did: the tail it looked at, the bytes it moved out of it, and the on-disk
   * bytes the segment lost.
   */
  private record Shrink(long windowBytes, long moved, long truncated) {}

  /**
   * What one tail shrink found and did, kept around for {@link BlobShrinkTest} to inspect directly
   * — the fields behind the INFO line {@link #shrinkTail} logs, without parsing it back out of
   * text. Package-private for that one reason.
   */
  record ShrinkReport(
      int segment,
      long liveValues,
      long ownedChunks,
      long ownedBlobs,
      long bytesMoved,
      long notOwnedStaging,
      long notOwnedOther,
      long toastDeadTuples,
      long toastLiveTuples,
      long pagesBefore,
      long pagesAfter,
      long bytesTruncated,
      String vacuumSummary) {
    long notOwned() {
      return notOwnedStaging + notOwnedOther;
    }
  }

  /** The most recent {@link #shrinkTail} call's report, for {@link BlobShrinkTest} alone. */
  private volatile ShrinkReport lastShrinkReport;

  /** Package-private accessor for {@link BlobShrinkTest}; nothing else needs the raw numbers. */
  ShrinkReport lastShrinkReport() {
    return lastShrinkReport;
  }

  /** A partition's TOAST relation (a regclass-quoted name), its oid, its size in pages, the block size. */
  record Toast(String name, long oid, long pages, long blockSize) {}

  /**
   * Shrinks an oversized closed segment from the tail of its TOAST relation by at most {@code
   * budget} bytes.
   *
   * <p><b>Why an oversized segment is not drained like the others.</b> Found live on qits-2248's
   * first day: the upgrade attached the whole pre-existing table as segment 0, 20 GB on disk and 92%
   * live. The ratio rule leaves such a segment alone, so every blob the gc deleted from it returned
   * nothing — and, worse than before the upgrade, the space it freed was no longer reused either,
   * since new chunks go only into the open segment. Disk grew by everything deleted from it, and the
   * full drain that would eventually follow needs half the segment as free disk at once. A segment
   * that large must instead give disk back in bounded steps, whatever its live share.
   *
   * <p><b>The one way PostgreSQL gives part of a table back is a VACUUM truncating empty pages off
   * its end.</b> So the shrink empties the end:
   *
   * <ol>
   *   <li>The tail is the last {@code budget} bytes of the partition's TOAST relation, from block
   *       {@code N = max(0, pages - budget / block_size)} on.
   *   <li>The live TOAST values there are read with a TID range scan ({@code ctid >= '(N,0)'}), which
   *       reads those pages only, and mapped to their chunks through {@code
   *       pg_column_toast_chunk_id} on the partition's heap — one small row per chunk, cheap to scan.
   *       Only chunks some blob owns qualify: STAGING content is never moved.
   *   <li>Exactly those chunks — not the rest of their blob, which stays put until the tail reaches
   *       it — move into the open segment by the drain's own per-blob move, under the blob's lock,
   *       rolling it whenever it fills.
   *   <li>{@code VACUUM (INDEX_CLEANUP ON, TRUNCATE true, SKIP_LOCKED true)} on the partition prunes
   *       the moved and deleted tuples and cuts the now-empty pages off the end of the TOAST relation
   *       (and the heap), which returns them to the filesystem.
   * </ol>
   *
   * <p><b>Why {@code INDEX_CLEANUP} is forced {@code ON}.</b> Found live on the 20 GB segment 0,
   * after a shrink had already moved the tail's chunks out: {@code tail from TOAST block 2263563 of
   * 2394635 (window 1073741824 bytes): 0 live values, ... toast dead/live tuples 0/4663910 ...; pages
   * 2394635 -&gt; 2394635, truncated 0 bytes; vacuum: ... index scan bypassed: 23352 pages from table
   * (0.98% of total) have 93333 dead item identifiers}. The tail held no live tuples at all — the
   * values were gone — yet nothing truncated. With {@code INDEX_CLEANUP AUTO} (the default), VACUUM
   * skips index vacuuming whenever fewer than {@code BYPASS_THRESHOLD_PAGES} — 2% of the relation's
   * pages — carry an {@code LP_DEAD} line pointer; here only 0.98% did, so it bypassed. A bypassed
   * page's {@code LP_DEAD} pointers are never turned into {@code LP_UNUSED}, and truncation treats
   * any page with a used line pointer, dead or live, as nondeletable — so the pages the tail had just
   * emptied of values still counted as occupied, and truncation, which only ever cuts a contiguous
   * run of fully-unused pages off the end, cut nothing. 23352 pages is about the 186 MB a previous
   * run moved out of this same tail: the move did its job, and only the bypassed index cleanup kept
   * the pages from being given back. {@code INDEX_CLEANUP ON} disables the bypass, so those line
   * pointers are retired and the truly empty tail truncates on the run that emptied it, at the cost
   * of a full scan of the TOAST relation's index on every shrink that has anything to remove — paid
   * once per gc run, which is cheap enough to always pay.
   *
   * <p><b>Why the tail is found by its exact TOAST position, and nothing cheaper.</b> The bytes that
   * take the disk are in the TOAST relation, and only emptying its last pages lets the VACUUM cut
   * anything off. A stand-in for that position was tried and failed live: choosing the newest heap
   * rows by {@code ctid}, which needs no access to {@code pg_toast}, moved 1,074,593,353 bytes out
   * of the 20 GB segment 0 in one run and truncated 770,048 — heap pages only — so every gc run
   * <em>grew</em> the disk by about 1.1 GB. The heap's order does not follow the TOAST relation's
   * there: new chunks reuse free slots low in the heap while their TOAST values append at the
   * relation's end. Moving chunks that are not in the TOAST tail costs their full size and returns
   * nothing, so the shrink reads the tail itself or does not run.
   *
   * <p><b>That needs USAGE on schema {@code pg_toast}</b>, which a table's owner is not granted.
   * Compaction asks for it once per run ({@link #toastAccess}) and, without it, leaves every
   * oversized segment as it is and logs the grant to make; the platform's deployer makes it for
   * each service role in its own database.
   *
   * <p><b>What can make a run return less.</b> The truncation stops at the last live tuple, so a
   * STAGING upload in the tail holds the tail until it is promoted (the next run moves it) or
   * reaped. VACUUM can only remove tuples no snapshot can still see, so a long transaction elsewhere
   * keeps the moved chunks' old copies until it ends. And the truncation needs a moment's ACCESS
   * EXCLUSIVE lock on the partition, which VACUUM only ever tries for — it never queues, so no read
   * waits on it, and if it cannot get it the pages stay until the next run. In every case the moved
   * chunks are correctly in the open segment; only the return is late. One floor is PostgreSQL's
   * own: VACUUM truncates only when at least 1000 pages (8 MB) or a sixteenth of the relation can
   * go, so a budget under 8 MiB on a large segment would move chunks and truncate nothing.
   *
   * <p><b>Cost.</b> Up to {@code budget} bytes moved (plus one chunk at the edge), and a VACUUM of
   * the partition — which skips the pages its visibility map knows clean, but, with the bypass
   * forced off, scans the TOAST relation's index whole on every run that has anything to remove
   * from it.
   */
  private Shrink shrinkTail(Partition partition, long budget) {
    Toast toast = toastOf(partition.name());
    if (toast == null || toast.pages() == 0) {
      return new Shrink(0, 0, 0);
    }
    long startBlock = Math.max(0, toast.pages() - budget / toast.blockSize());
    long windowBytes = (toast.pages() - startBlock) * toast.blockSize();
    TailSummary tail = tailSummary(partition.name(), toast.name(), startBlock);
    long moved = 0;
    for (Map.Entry<Owned, List<Integer>> blob : tail.owned().entrySet()) {
      List<Integer> seqs = blob.getValue();
      for (int from = 0; from < seqs.size(); from += DRAIN_BATCH) {
        long batch =
            moveSeqs(
                blob.getKey(),
                partition.segment(),
                seqs.subList(from, Math.min(seqs.size(), from + DRAIN_BATCH)));
        if (batch < 0) {
          break;
        }
        moved += batch;
      }
      // After each blob, not once at the end: a budget can be as large as a segment, so a whole
      // tail moved into one open segment can push it past twice the segment size — oversized
      // itself, and shrunk again by a later run, moving the same chunks twice.
      rollIfFull();
    }
    // Read before the VACUUM below, so the dead/live counts and the timestamps describe what this
    // run found on arrival — autovacuum's own account, not anything our VACUUM is about to produce.
    ToastStats stats = toastStats(toast.oid());
    long beforeVacuum = totalSize(partition.name());
    List<String> vacuumMessages = vacuum(partition.name());
    long truncated = Math.max(0, beforeVacuum - totalSize(partition.name()));
    Toast toastAfter = toastOf(partition.name());
    long pagesAfter = toastAfter == null ? 0 : toastAfter.pages();
    long ownedChunks = tail.ownedChunks();
    long ownedBlobs = tail.ownedBlobs();
    String vacuumSummary = condenseVacuumMessages(vacuumMessages);
    lastShrinkReport =
        new ShrinkReport(
            partition.segment(),
            tail.liveValues(),
            ownedChunks,
            ownedBlobs,
            moved,
            tail.notOwnedStaging(),
            tail.notOwnedOther(),
            stats.deadTuples(),
            stats.liveTuples(),
            toast.pages(),
            pagesAfter,
            truncated,
            vacuumSummary);
    // The four live causes a flat "0 bytes truncated" cannot tell apart on its own: (a) VACUUM
    // skipped a conflicting lock (the vacuum part names it), (b) VACUUM ran but could not get the
    // brief ACCESS EXCLUSIVE lock to truncate (same), (c) dead tuples an old snapshot still pins
    // (the dead/live tuple counts and the last-vacuum timestamps), (d) live tail content with no
    // owning blob — STAGING or otherwise — holding the truncation back below it (not owned > 0).
    LOG.infof(
        "Blob segment %d: tail from TOAST block %d of %d (window %d bytes): %d live values, %d"
            + " owned (%d chunks of %d blobs moved, %d bytes), %d not owned (%d staging, %d other);"
            + " toast dead/live tuples %d/%d, last autovacuum %s, last vacuum %s; pages %d -> %d,"
            + " truncated %d bytes; vacuum: %s",
        partition.segment(),
        startBlock,
        toast.pages(),
        windowBytes,
        tail.liveValues(),
        ownedChunks,
        ownedChunks,
        ownedBlobs,
        moved,
        tail.notOwnedStaging() + tail.notOwnedOther(),
        tail.notOwnedStaging(),
        tail.notOwnedOther(),
        stats.deadTuples(),
        stats.liveTuples(),
        formatStatsTimestamp(stats.lastAutovacuum()),
        formatStatsTimestamp(stats.lastVacuum()),
        toast.pages(),
        pagesAfter,
        truncated,
        vacuumSummary);
    return new Shrink(windowBytes, moved, truncated);
  }

  /** {@code pg_stat_all_tables}'s timestamp columns are null until the first vacuum ever runs. */
  private static String formatStatsTimestamp(OffsetDateTime at) {
    return at == null ? "never" : at.toString();
  }

  /**
   * Whether the role this store connects as may read {@code pg_toast}, and that role's name.
   *
   * @param usage {@code has_schema_privilege('pg_toast', 'USAGE')} for the current user
   * @param role {@code current_user}, for the log line that names the grant to make
   */
  private record ToastAccess(boolean usage, String role) {}

  /**
   * Asks the database whether the current role holds USAGE on schema {@code pg_toast} — the one
   * privilege {@link #shrinkTail} needs beyond owning the blob tables.
   *
   * <p><b>Asked, never assumed or worked around.</b> A table's owner is granted no USAGE on {@code
   * pg_toast}, so a consumer's service role — which owns the blob tables and is no superuser —
   * cannot name its own partitions' TOAST relations without a grant: the first cut of the shrink
   * failed live on exactly that, {@code permission denied for schema pg_toast}. The fallback that
   * needed no grant, choosing the tail by heap order, then failed worse (see {@link #shrinkTail}).
   * So without the privilege there is no safe shrink, and compaction skips oversized segments
   * outright — moves nothing, vacuums nothing — and says, once per run, which grant to make.
   *
   * <p>The platform's deployer grants {@code USAGE ON SCHEMA pg_toast} to each service role in its
   * own database. The grant is narrow: USAGE only lets the role <em>name</em> relations in the
   * schema; every TOAST relation keeps the ACL of the table it belongs to, whose owner is its owner,
   * so a role granted it can read the TOAST of its own tables and of nothing else.
   */
  private ToastAccess toastAccess() {
    return db.autocommit(
        "Failed to ask whether this role may read pg_toast",
        connection -> {
          try (Statement select = connection.createStatement();
              ResultSet row =
                  select.executeQuery(
                      "select has_schema_privilege('pg_toast', 'USAGE'), current_user::text")) {
            row.next();
            return new ToastAccess(row.getBoolean(1), row.getString(2));
          }
        });
  }

  /** The partition's TOAST relation and its size, or null if it has none. */
  Toast toastOf(String partition) {
    return db.autocommit(
        "Failed to find the TOAST relation of " + partition,
        connection -> {
          try (PreparedStatement select =
              connection.prepareStatement(
                  "select t.oid::regclass::text, t.oid::bigint,"
                      + " pg_relation_size(t.oid) / current_setting('block_size')::bigint,"
                      + " current_setting('block_size')::bigint"
                      + " from pg_class c join pg_class t on t.oid = c.reltoastrelid"
                      + " where c.oid = to_regclass(?)")) {
            select.setString(1, partition);
            try (ResultSet row = select.executeQuery()) {
              return row.next()
                  ? new Toast(row.getString(1), row.getLong(2), row.getLong(3), row.getLong(4))
                  : null;
            }
          }
        });
  }

  /** The TOAST relation's own stat-view counters: dead/live tuples, and when it was last vacuumed. */
  private record ToastStats(
      long deadTuples, long liveTuples, OffsetDateTime lastAutovacuum, OffsetDateTime lastVacuum) {}

  /**
   * Reads {@code pg_stat_all_tables} for one TOAST relation by its oid. Unlike the relation's own
   * rows, its stats need no {@link #toastAccess} grant — the view is readable for any table a role
   * can see at all, which the role's ownership of the partition already gives it.
   *
   * <p>Read once per shrink, right before the VACUUM {@link #shrinkTail} is about to issue, so the
   * counts and timestamps describe what autovacuum (or the previous run) left behind — telling a
   * tail that is genuinely clean apart from one that is not, both of which VACUUM alone, run again,
   * cannot distinguish on its own.
   */
  private ToastStats toastStats(long oid) {
    return db.autocommit(
        "Failed to read TOAST stats for relation " + oid,
        connection -> {
          try (PreparedStatement select =
              connection.prepareStatement(
                  "select n_dead_tup, n_live_tup, last_autovacuum, last_vacuum"
                      + " from pg_stat_all_tables where relid = ?")) {
            select.setLong(1, oid);
            try (ResultSet row = select.executeQuery()) {
              if (!row.next()) {
                return new ToastStats(0, 0, null, null);
              }
              return new ToastStats(
                  row.getLong(1),
                  row.getLong(2),
                  row.getObject(3, OffsetDateTime.class),
                  row.getObject(4, OffsetDateTime.class));
            }
          }
        });
  }

  /**
   * The statement that lists the TOAST values with a tuple at or past {@code startBlock}: a TID
   * range scan, which reads the tail pages and no others. A statement of its own, on purpose — folded
   * into the chunk lookup as a subquery, the planner is free to probe the TOAST index once per heap
   * row instead, which on segment 0 reads the whole relation the shrink exists to avoid. The name
   * is regclass output, already quoted; the block is a number.
   */
  static String tailValuesSql(String toast, long startBlock) {
    return "select distinct chunk_id::bigint from "
        + toast
        + " where ctid >= '("
        + startBlock
        + ",0)'::tid";
  }

  /**
   * What the tail scan of one partition found: how many live TOAST values sit in the window
   * ({@link #tailValuesSql}), the owned chunks among them by blob, and how many are not owned at
   * all — split into STAGING (an upload still being written, never moved) and other (promoted
   * content with no blob row, which {@link #drain} already treats as not this run's either).
   */
  private record TailSummary(
      long liveValues, Map<Owned, List<Integer>> owned, long notOwnedStaging, long notOwnedOther) {
    long ownedChunks() {
      return owned.values().stream().mapToInt(List::size).sum();
    }

    long ownedBlobs() {
      return owned.size();
    }
  }

  /**
   * The tail's live TOAST values, by whether a blob owns the chunk they belong to. One heap scan
   * answers both the owned mapping and the not-owned split, instead of two: the value ids come from
   * {@link #tailValuesSql}; matching them back scans the partition's heap once — one small row per
   * chunk, with {@code pg_column_toast_chunk_id} reading only the TOAST pointer — and the not-owned
   * count is exactly what tells a tail still held by STAGING (or other unowned) content apart from
   * one VACUUM simply could not truncate, the two causes a bare byte count cannot distinguish.
   */
  private TailSummary tailSummary(String partition, String toast, long startBlock) {
    return db.autocommit(
        "Failed to read the tail of " + partition,
        connection -> {
          List<Long> values = new ArrayList<>();
          try (Statement select = connection.createStatement();
              ResultSet rows = select.executeQuery(tailValuesSql(toast, startBlock))) {
            while (rows.next()) {
              values.add(rows.getLong(1));
            }
          }
          Map<Owned, List<Integer>> owned = new LinkedHashMap<>();
          long staging = 0;
          long other = 0;
          if (!values.isEmpty()) {
            try (PreparedStatement select =
                connection.prepareStatement(
                    "select p.content_id, b.id, c.state, p.seq from "
                        + partition
                        + " p join blob_content c on c.content_id = p.content_id"
                        + " left join blob b on b.content_id = p.content_id"
                        + " where pg_column_toast_chunk_id(p.bytes)::bigint = any(?)"
                        + " order by p.content_id, p.seq")) {
              select.setArray(1, connection.createArrayOf("int8", values.toArray()));
              try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                  String blobId = rows.getString(2);
                  if (blobId != null) {
                    Owned key = new Owned(rows.getObject(1, UUID.class), blobId);
                    owned.computeIfAbsent(key, k -> new ArrayList<>()).add(rows.getInt(4));
                  } else if ("STAGING".equals(rows.getString(3))) {
                    staging++;
                  } else {
                    other++;
                  }
                }
              }
            }
          }
          return new TailSummary(values.size(), owned, staging, other);
        });
  }

  private long totalSize(String partition) {
    return db.autocommit(
        "Failed to measure " + partition,
        connection -> {
          try (PreparedStatement select =
              connection.prepareStatement(
                  "select coalesce(pg_total_relation_size(to_regclass(?)), 0)")) {
            select.setString(1, partition);
            try (ResultSet row = select.executeQuery()) {
              row.next();
              return row.getLong(1);
            }
          }
        });
  }

  /**
   * A VACUUM of one partition, with truncation asked for explicitly so a {@code vacuum_truncate =
   * off} reloption cannot quietly turn the shrink into a no-op. Never inside a transaction — VACUUM
   * refuses one — which the autocommit connection guarantees.
   *
   * <p>{@code INDEX_CLEANUP ON}: also asked for explicitly, not left at the {@code AUTO} default.
   * Found live on segment 0: a tail the shrink had just emptied of live values still truncated
   * nothing, because {@code AUTO} let PostgreSQL's own bypass optimisation skip index vacuuming —
   * see {@link #shrinkTail} for the INFO line that caught it and the mechanism. Forcing it on costs
   * a full scan of the TOAST relation's index on a run that has anything to remove, which is the
   * price of a truncation that actually happens.
   *
   * <p>{@code SKIP_LOCKED}: if anything holds a lock that conflicts — an autovacuum of the same
   * partition, typically — this run skips the VACUUM rather than wait behind it, and the next one
   * truncates. A failure is logged, not thrown: the chunks it would have pruned already moved and
   * committed, and the compaction's other segments are still worth doing.
   *
   * <p>{@code VERBOSE true}: PostgreSQL reports VACUUM's own account of what it did — truncated
   * page counts, dead/removable tuple counts, the removable cutoff, a skipped lock — as INFO-level
   * server messages on the statement, not as anything a {@code RETURNING} clause or a result set
   * could carry. pgjdbc surfaces every NOTICE, INFO and WARNING severity as a chained {@link
   * SQLWarning} on the {@link Statement} that ran it ({@code Statement.getWarnings()}, followed by
   * {@code getNextWarning()}); {@link BlobShrinkTest} asserts that chain is not empty, so a pgjdbc
   * upgrade that stopped surfacing INFO would fail a test rather than go quietly unnoticed in a
   * log line nobody reads until the next live incident.
   *
   * @return the server's messages for this statement, oldest first, for {@link #shrinkTail} to fold
   *     into its one INFO line
   */
  private List<String> vacuum(String partition) {
    try {
      return db.autocommit(
          "Failed to vacuum " + partition,
          connection -> {
            try (Statement sql = connection.createStatement()) {
              sql.execute(vacuumSql(partition));
              List<String> messages = new ArrayList<>();
              for (SQLWarning warning = sql.getWarnings();
                  warning != null;
                  warning = warning.getNextWarning()) {
                messages.add(warning.getMessage());
              }
              return messages;
            }
          });
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not vacuum %s this run; the next run truncates it", partition);
      return List.of();
    }
  }

  /**
   * The exact statement {@link #vacuum} runs, pulled out so {@link BlobShrinkTest} can assert on
   * the SQL text itself — in particular that {@code INDEX_CLEANUP} is forced {@code ON} — rather
   * than re-deriving it, since a real bypass only reproduces on a relation far larger than the
   * suite can afford (see the test for why).
   */
  static String vacuumSql(String partition) {
    return "vacuum (verbose true, index_cleanup on, truncate true, skip_locked true) " + partition;
  }

  /**
   * The VERBOSE lines worth keeping in one INFO line. VACUUM VERBOSE's full account runs to dozens
   * of lines — per-index statistics, buffer counts, the oldest xmin it found — most of which repeat
   * the same shape every run and answer nothing {@link #shrinkTail}'s log needs. These keywords are
   * the ones that do: whether it truncated pages, skipped a lock, found tuples removable or dead,
   * or named the removable cutoff.
   */
  private static final Pattern VACUUM_KEEP =
      Pattern.compile("(?i)truncat|skipping|removable|dead|cutoff|lock");

  /** How long {@link #condenseVacuumMessages} lets its join run before cutting it off. */
  private static final int VACUUM_SUMMARY_CAP = 1500;

  /**
   * Splits every VACUUM message into lines, keeps the ones {@link #VACUUM_KEEP} matches, and joins
   * them with {@code " | "} — capped so one segment's worth of VERBOSE output never dwarfs the rest
   * of the log line it is folded into.
   */
  private static String condenseVacuumMessages(List<String> messages) {
    String joined =
        messages.stream()
            .flatMap(message -> Arrays.stream(message.split("\\r?\\n")))
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> VACUUM_KEEP.matcher(line).find())
            .collect(Collectors.joining(" | "));
    return joined.length() > VACUUM_SUMMARY_CAP ? joined.substring(0, VACUUM_SUMMARY_CAP) : joined;
  }

  /** One partition of {@code blob_chunk}: its segment, its quoted name and its on-disk size. */
  private record Partition(int segment, String name, long diskBytes) {}

  /** Closed segments older than {@code openAtStart}, oldest first, read from the catalog. */
  private List<Partition> closedPartitions(int openAtStart) {
    return db.autocommit(
        "Failed to list the blob segments",
        connection -> {
          try (Statement select = connection.createStatement();
              ResultSet rows =
                  select.executeQuery(
                      "select c.oid::regclass::text, pg_get_expr(c.relpartbound, c.oid),"
                          + " pg_total_relation_size(c.oid)"
                          + " from pg_inherits i join pg_class c on c.oid = i.inhrelid"
                          + " where i.inhparent = 'blob_chunk'::regclass")) {
            List<Partition> found = new ArrayList<>();
            while (rows.next()) {
              Matcher bound = LIST_BOUND.matcher(String.valueOf(rows.getString(2)));
              if (!bound.find()) {
                // Not a partition this store made; leave it alone rather than guess at it.
                continue;
              }
              int segment = Integer.parseInt(bound.group(1));
              if (segment < openAtStart) {
                found.add(new Partition(segment, rows.getString(1), rows.getLong(3)));
              }
            }
            found.sort((a, b) -> Integer.compare(a.segment(), b.segment()));
            return found;
          }
        });
  }

  /** A segment's rows and the summed stored size of their chunk values. */
  private record Live(long rows, long bytes) {}

  /**
   * How much of a segment is live. {@code pg_column_size} of an out-of-line value is its stored
   * size read from the TOAST pointer, so this scans the small heap and never fetches a byte of
   * blob content.
   */
  private Live liveShare(int segment) {
    return db.autocommit(
        "Failed to measure blob segment " + segment,
        connection -> {
          try (PreparedStatement select =
              connection.prepareStatement(
                  "select count(*), coalesce(sum(pg_column_size(bytes)), 0)"
                      + " from blob_chunk where segment = ?")) {
            select.setInt(1, segment);
            try (ResultSet row = select.executeQuery()) {
              row.next();
              return new Live(row.getLong(1), row.getLong(2));
            }
          }
        });
  }

  /** One promoted content with chunks in the segment being drained, and the blob that owns it. */
  private record Owned(UUID contentId, String blobId) {}

  /**
   * Moves every promoted blob's chunks out of {@code segment} into the open one.
   *
   * <p>Joined to {@code blob}: a content with no blob row is STAGING (or being deleted right now),
   * and either way is not this run's to move. That join is also the content-to-blob mapping the
   * advisory lock needs, since the lock is keyed on the address, not the content.
   *
   * @return the bytes moved
   */
  private long drain(int segment) {
    List<Owned> owned =
        db.autocommit(
            "Failed to list blob segment " + segment,
            connection -> {
              try (PreparedStatement select =
                  connection.prepareStatement(
                      "select distinct c.content_id, b.id from blob_chunk c"
                          + " join blob b on b.content_id = c.content_id where c.segment = ?")) {
                select.setInt(1, segment);
                try (ResultSet rows = select.executeQuery()) {
                  List<Owned> found = new ArrayList<>();
                  while (rows.next()) {
                    found.add(new Owned(rows.getObject(1, UUID.class), rows.getString(2)));
                  }
                  return found;
                }
              }
            });
    long moved = 0;
    for (Owned blob : owned) {
      moved += drainBlob(blob, segment);
    }
    return moved;
  }

  /**
   * Moves one blob's chunks out of {@code segment}, {@link #DRAIN_BATCH} at a time.
   *
   * <p>The chunk range is read once: promoted content never gains a chunk. Each batch then re-checks
   * under the blob's advisory lock that the blob still owns the content — a delete that won the lock
   * first has removed it, cascade and all, and there is nothing left to move.
   */
  private long drainBlob(Owned blob, int segment) {
    int[] range =
        db.autocommit(
            "Failed to read blob chunk range",
            connection -> {
              try (PreparedStatement select =
                  connection.prepareStatement(
                      "select min(seq), max(seq) from blob_chunk"
                          + " where content_id = ? and segment = ?")) {
                select.setObject(1, blob.contentId());
                select.setInt(2, segment);
                try (ResultSet row = select.executeQuery()) {
                  row.next();
                  int min = row.getInt(1);
                  return row.wasNull() ? null : new int[] {min, row.getInt(2)};
                }
              }
            });
    if (range == null) {
      return 0;
    }
    long moved = 0;
    for (int from = range[0]; from <= range[1]; from += DRAIN_BATCH) {
      long batch = moveBatch(blob, segment, from, from + DRAIN_BATCH);
      if (batch < 0) {
        break;
      }
      moved += batch;
    }
    return moved;
  }

  /**
   * One transaction: copy chunks {@code [from, to)} of one blob from {@code segment} into the open
   * segment, then delete them from {@code segment}. The copy names its target the way a chunk insert
   * does, so a roll mid-run is followed rather than raced.
   *
   * @return the bytes moved, or -1 if the blob no longer owns its content
   */
  private long moveBatch(Owned blob, int segment, int from, int to) {
    return moveChunks(
        blob,
        segment,
        "seq >= ? and seq < ?",
        (statement, index) -> {
          statement.setInt(index, from);
          statement.setInt(index + 1, to);
        });
  }

  /**
   * {@link #moveBatch}, for an explicit set of chunk numbers rather than a range — what the tail
   * shrink moves: exactly the chunks whose bytes sit in the tail, and not the rest of their blob.
   */
  private long moveSeqs(Owned blob, int segment, List<Integer> seqs) {
    return moveChunks(
        blob,
        segment,
        "seq = any(?)",
        (statement, index) ->
            statement.setArray(
                index, statement.getConnection().createArrayOf("int4", seqs.toArray())));
  }

  /** Binds the parameters of a {@link #moveChunks} chunk filter, starting at {@code index}. */
  @FunctionalInterface
  private interface SeqBinding {
    void bind(PreparedStatement statement, int index) throws SQLException;
  }

  /**
   * The one move both drains share: under the blob's advisory lock, and only while the blob still
   * owns the content, copy the chunks {@code seqFilter} selects into the open segment and delete
   * them from {@code segment}, in one transaction.
   *
   * @return the bytes moved, or -1 if the blob no longer owns its content
   */
  private long moveChunks(Owned blob, int segment, String seqFilter, SeqBinding seqs) {
    return db.inOwnTransaction(
        "Failed to move blob " + blob.blobId() + " out of segment " + segment,
        connection -> {
          BlobStore.lockBlob(connection, blob.blobId());
          try (PreparedStatement owns =
              connection.prepareStatement("select 1 from blob where id = ? and content_id = ?")) {
            owns.setString(1, blob.blobId());
            owns.setObject(2, blob.contentId());
            try (ResultSet row = owns.executeQuery()) {
              if (!row.next()) {
                return -1L;
              }
            }
          }
          long rows;
          long bytes;
          try (PreparedStatement copy =
              connection.prepareStatement(
                  "with copied as ("
                      + " insert into blob_chunk (content_id, seq, bytes, segment)"
                      + " select content_id, seq, bytes, (select segment from blob_segment)"
                      + " from blob_chunk"
                      + " where content_id = ? and segment = ? and "
                      + seqFilter
                      + " returning octet_length(bytes) as length)"
                      + " select count(*), coalesce(sum(length), 0) from copied")) {
            copy.setObject(1, blob.contentId());
            copy.setInt(2, segment);
            seqs.bind(copy, 3);
            try (ResultSet row = copy.executeQuery()) {
              row.next();
              rows = row.getLong(1);
              bytes = row.getLong(2);
            }
          }
          try (PreparedStatement delete =
              connection.prepareStatement(
                  "delete from blob_chunk where content_id = ? and segment = ? and " + seqFilter)) {
            delete.setObject(1, blob.contentId());
            delete.setInt(2, segment);
            seqs.bind(delete, 3);
            int deleted = delete.executeUpdate();
            if (deleted != rows) {
              // Cannot happen under the blob's lock; if it ever does, the rollback keeps every
              // chunk exactly where it was, which is the only safe answer.
              throw new SQLException(
                  "copied " + rows + " chunks of " + blob.blobId() + " but deleted " + deleted);
            }
          }
          return bytes;
        });
  }

  /**
   * Drops a closed segment's partition if, and only if, it holds no row.
   *
   * <p>The emptiness check and the drop share one transaction under an ACCESS EXCLUSIVE lock on the
   * <em>parent</em>. That lock is what DROP needs anyway (a partition cannot be dropped without
   * it), and taking it first, explicitly, is what makes the check true at the drop: no chunk
   * statement can be running against any partition while it is held. It is taken with {@link
   * #LOCK_TIMEOUT}, so a long statement elsewhere costs this run its drop — the segment is "held"
   * and the next run tries again — rather than costing every blob read a stall.
   *
   * <p>A STAGING content still in the segment is exactly how it stays non-empty: the upload's
   * chunks stay where they are until it promotes or the staging sweep reaps it.
   */
  private boolean dropIfEmpty(Partition partition) {
    try {
      return db.inOwnTransaction(
          "Failed to drop blob segment " + partition.segment(),
          connection -> {
            try (Statement sql = connection.createStatement()) {
              sql.execute("set local lock_timeout = '" + LOCK_TIMEOUT + "'");
              sql.execute("lock table only blob_chunk in access exclusive mode");
              try (PreparedStatement any =
                  connection.prepareStatement(
                      "select exists (select 1 from blob_chunk where segment = ?)")) {
                any.setInt(1, partition.segment());
                try (ResultSet row = any.executeQuery()) {
                  row.next();
                  if (row.getBoolean(1)) {
                    return false;
                  }
                }
              }
              sql.execute("drop table " + partition.name());
              return true;
            }
          });
    } catch (RuntimeException e) {
      LOG.warnf(
          e, "Could not drop blob segment %d this run; the next run tries again", partition.segment());
      return false;
    }
  }

  /** What compaction reports: the blob tables' and the database's on-disk bytes. */
  private record Sizes(long tables, long database) {}

  /**
   * The blob tables' on-disk size — every partition of {@code blob_chunk} with its TOAST and index,
   * plus {@code blob}, {@code blob_content} and {@code blob_segment} — and the whole database's.
   *
   * <p>{@code pg_partition_tree} of a plain table answers that table alone, so the same statement
   * measures an unsegmented store.
   */
  private Sizes measure() {
    return db.autocommit(
        "Failed to measure the blob tables",
        connection -> {
          try (Statement select = connection.createStatement();
              ResultSet row =
                  select.executeQuery(
                      "select (select coalesce(sum(pg_total_relation_size(relid)), 0)"
                          + " from pg_partition_tree('blob_chunk'))"
                          + " + pg_total_relation_size('blob')"
                          + " + pg_total_relation_size('blob_content')"
                          + " + coalesce(pg_total_relation_size(to_regclass('blob_segment')), 0),"
                          + " pg_database_size(current_database())")) {
            row.next();
            return new Sizes(row.getLong(1), row.getLong(2));
          }
        });
  }

  private static void lockNamed(Connection connection, String name) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      lock.setString(1, name);
      lock.execute();
    }
  }

  private static boolean tryLockSession(Connection connection) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("select pg_try_advisory_lock(hashtextextended(?, 0))")) {
      lock.setString(1, COMPACTION_LOCK);
      try (ResultSet row = lock.executeQuery()) {
        row.next();
        return row.getBoolean(1);
      }
    }
  }

  /** Releases the session lock before the connection goes back to the pool, or it would leak. */
  private static void unlockSession(Connection connection) throws SQLException {
    try (PreparedStatement unlock =
        connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
      unlock.setString(1, COMPACTION_LOCK);
      unlock.execute();
    }
  }
}
