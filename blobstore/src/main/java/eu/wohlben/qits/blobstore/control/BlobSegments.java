package eu.wohlben.qits.blobstore.control;

import eu.wohlben.qits.blobstore.control.BlobCompaction.CompactionResult;
import eu.wohlben.qits.blobstore.error.InternalServerErrorException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * ever touches more than one blob's chunks or holds a lock wider than a moment.
 *
 * <p><b>The invariants that make it safe</b>, each restated where it is enforced:
 *
 * <ul>
 *   <li><b>A chunk insert names its segment in the statement that writes it</b> — {@code
 *       (select segment from blob_segment)} in the {@code VALUES} — so there is no window between
 *       choosing a segment and writing into it beyond one statement's own snapshot. A roll holds
 *       {@code blob_segment} exclusively while it attaches the next partition, so a statement that
 *       reads the new number always routes against a partition list that contains it.
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
   * cannot happen while a roll holds {@code blob_segment} exclusively (see {@link #roll}) and a drop
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
   * ATTACH takes only SHARE UPDATE EXCLUSIVE there, which reads and writes pass straight through.
   * {@code LIKE ... INCLUDING ALL} copies the columns, the external storage of {@code bytes} and
   * the primary key, so ATTACH adopts that key as the partition's half of the parent's and clones
   * the foreign key onto an empty table — nothing to validate.
   *
   * <p><b>But the weak lock opens a race, and {@code blob_segment} closes it.</b> Because ATTACH
   * does not conflict with a writer's lock on the parent, a chunk insert can take that lock — and
   * with it the parent's partition list — before the roll commits, then take its snapshot after,
   * read the NEW open segment, and find no partition for it in the list it holds ("no partition of
   * relation found for row"). Measured: nine such inserts in forty runs of the concurrency suite.
   * So the roll first takes ACCESS EXCLUSIVE on {@code blob_segment} itself and holds it to the
   * commit. Every statement that names the open segment reads that table, so it queues there; and
   * a lock granted after waiting processes the invalidations the roll's commit sent, so the parent's
   * partition list it then routes against already includes the new partition. Chunk inserts pause
   * for the few milliseconds a roll takes; chunk reads never touch {@code blob_segment} and do not.
   *
   * <p>Under a store-wide advisory lock, and only if {@code blob_segment} still says {@code from}:
   * every replica's promotes see the same full segment, and exactly one of them rolls.
   *
   * @return whether this call rolled; false if another process got there first
   */
  boolean roll(int from) {
    int next = from + 1;
    String partition = PARTITION_PREFIX + next;
    boolean rolled =
        db.inOwnTransaction(
            "Failed to roll the open blob segment",
            connection -> {
              try (Statement sql = connection.createStatement()) {
                sql.execute("set local lock_timeout = '" + LOCK_TIMEOUT + "'");
                lockNamed(connection, ROLL_LOCK);
                // Before the attach, and held to the commit: see the method comment for why every
                // reader of the open segment must queue behind the whole roll.
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
          before.tables(), before.tables(), before.database(), before.database(), 0, 0, 0, 0);
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
                before.tables(), before.tables(), before.database(), before.database(), 0, 0, 0, 0);
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
    long moved = 0;
    for (Partition closed : closedPartitions(openAtStart)) {
      Live live = liveShare(closed.segment());
      boolean empty = live.rows() == 0;
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
            moved);
    LOG.infof(
        "Blob compaction: drained %d segments (%d bytes moved), dropped %d, held %d; blob tables"
            + " %d -> %d bytes on disk, database %d -> %d bytes",
        drained,
        moved,
        dropped,
        held,
        before.tables(),
        after.tables(),
        before.database(),
        after.database());
    return result;
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
                      + " where content_id = ? and segment = ? and seq >= ? and seq < ?"
                      + " returning octet_length(bytes) as length)"
                      + " select count(*), coalesce(sum(length), 0) from copied")) {
            bindRange(copy, blob.contentId(), segment, from, to);
            try (ResultSet row = copy.executeQuery()) {
              row.next();
              rows = row.getLong(1);
              bytes = row.getLong(2);
            }
          }
          try (PreparedStatement delete =
              connection.prepareStatement(
                  "delete from blob_chunk"
                      + " where content_id = ? and segment = ? and seq >= ? and seq < ?")) {
            bindRange(delete, blob.contentId(), segment, from, to);
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

  private static void bindRange(
      PreparedStatement statement, UUID contentId, int segment, int from, int to)
      throws SQLException {
    statement.setObject(1, contentId);
    statement.setInt(2, segment);
    statement.setInt(3, from);
    statement.setInt(4, to);
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
