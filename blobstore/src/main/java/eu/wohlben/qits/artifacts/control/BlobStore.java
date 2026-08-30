package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.InternalServerErrorException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.PayloadTooLargeException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Content-addressed blob storage in PostgreSQL, decoupled from the metadata rows.
 *
 * <p>Bytes are 1 MiB {@code blob_chunk} rows under a surrogate content id; a {@code blob} row binds
 * the SHA-256 address to one content. <b>PostgreSQL is the only backend</b> — there is no
 * filesystem path, no temp directory and no fallback. The three tables ship as reference DDL at
 * {@code db/blobstore-tables.sql}, which each consumer copies into its own Flyway lineage, and the
 * datasource that reaches them is named by {@code qits.artifacts.blobs-datasource} (see {@link
 * BlobDb} for why a config key rather than an injected qualifier).
 *
 * <p><b>Staging lives in the database too.</b> A stage is a {@code STAGING} content id accumulating
 * chunks; promote inserts the {@code blob} row and flips the state, moving no bytes; a dedupe drops
 * the redundant staging content and the cascade takes its chunks. A process that dies mid-upload
 * leaves committed {@code STAGING} chunks — the exact analogue of yesterday's abandoned temp file,
 * and what {@link #sweepAbandonedStaging} exists for.
 *
 * <p><b>Writes and reads never hold a transaction open.</b> Chunk statements are autocommit
 * singles; only {@link #promote} and {@link #delete} open one, and both are short by construction.
 * A 1 GiB push is 1024 tiny transactions rather than one long one, so nothing pins a connection or
 * holds back the WAL visibility horizon while a client is slow.
 *
 * <p>Ids are checked before any statement runs — the shape defence that used to guard the fan-out
 * directories still guards these tables, and the DDL restates it as a check constraint.
 */
@ApplicationScoped
public class BlobStore {

  private static final Logger LOG = Logger.getLogger(BlobStore.class);

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  /** What a blob is chunked at unless a deployment says otherwise. See the config key below. */
  static final int DEFAULT_CHUNK_SIZE = 1 << 20;

  @Inject BlobDb db;

  /**
   * Bytes per chunk row. 1 MiB is the size the store is designed around: a 1 GiB layer is 1024 round
   * trips, an in-flight transfer costs one chunk of heap, and one chunk answers about sixteen of
   * JGit's block reads. <b>A deployment has no reason to change it.</b> The key exists because each
   * blob records the size it was written at, so the suite can exercise chunk boundaries thoroughly
   * without moving megabytes to do it.
   */
  @ConfigProperty(name = "qits.artifacts.blob-chunk-size", defaultValue = "1048576")
  int chunkSize;

  /**
   * How long a blob must have sat untouched before {@link #delete} will remove it. Seven days
   * covers any in-flight upload session by orders of magnitude — see that method for what the window
   * is for. The clock is the {@code stored_at} column, which is what {@code promote} stamped; it is
   * the file mtime this store used to read.
   */
  @ConfigProperty(name = "qits.artifacts.gc.blob-grace-period", defaultValue = "P7D")
  Duration blobGracePeriod;

  /**
   * How long an unpromoted staging area may sit before {@link #sweepAbandonedStaging} removes it.
   * One day is far longer than any upload session and far shorter than the grace window: an
   * abandoned stage is litter, not content.
   */
  @ConfigProperty(name = "qits.artifacts.staging-ttl", defaultValue = "P1D")
  Duration stagingTtl;

  /**
   * Every staging area this process has open. The sweep skips these, so an upload running longer
   * than the TTL is never cut from under itself.
   *
   * <p>A finished-but-unpromoted stage stays in here — that is an OCI upload session waiting for its
   * {@code PUT}, and it must survive the sweep. The set is process-local by design: a stage cannot
   * outlive the JVM that holds its running digest, so after a restart the sweep is free to reclaim
   * whatever was left mid-flight.
   */
  private final Set<UUID> openStages = ConcurrentHashMap.newKeySet();

  /** A blob staged in the database, not yet bound to its content address. */
  public record StagedBlob(String sha256, long size, UUID contentId) {}

  /** One {@code blob} row: everything a read needs. */
  private record Stored(UUID contentId, long size, int chunkSize, Instant storedAt) {}

  /**
   * Reclaims staging areas abandoned by a process that died mid-upload, older than {@code
   * qits.artifacts.staging-ttl} and not open here.
   *
   * <p>Public because the reclaiming is the consumer's to schedule: it runs once at startup below,
   * and a service with a garbage collector should also call it at the head of a sweep, where it is
   * the analogue of emptying the old temp directory.
   *
   * @return how many staging areas were removed
   */
  public int sweepAbandonedStaging() {
    Instant cutoff = Instant.now().minus(stagingTtl);
    List<UUID> abandoned =
        db.autocommit(
            "Failed to list abandoned blob staging",
            connection -> {
              try (PreparedStatement select =
                  connection.prepareStatement(
                      "select content_id from blob_content"
                          + " where state = 'STAGING' and started_at < ?")) {
                select.setObject(1, cutoff.atOffset(java.time.ZoneOffset.UTC));
                try (ResultSet rows = select.executeQuery()) {
                  List<UUID> found = new ArrayList<>();
                  while (rows.next()) {
                    found.add(rows.getObject(1, UUID.class));
                  }
                  return found;
                }
              }
            });
    int removed = 0;
    for (UUID contentId : abandoned) {
      if (!openStages.contains(contentId)) {
        removed += discardStaging(contentId);
      }
    }
    return removed;
  }

  /**
   * Drops this process's record of an open staging area, exactly as a restart does.
   *
   * <p>Package-private and here for the suite: while the process lives it protects everything it
   * opened, so the abandoned case is otherwise unreachable from a test. Nothing in production calls
   * this — a stage is forgotten by promoting or discarding it.
   */
  void forgetOpenStage(UUID contentId) {
    openStages.remove(contentId);
  }

  /**
   * One sweep at boot, because a crash is exactly when litter is left and a restart is exactly when
   * nothing is in flight. A failure here is logged, never fatal: the store's job is to serve bytes,
   * and refusing to start over uncollected litter would be the worse outcome.
   */
  void sweepAtStartup(@Observes StartupEvent started) {
    try {
      int removed = sweepAbandonedStaging();
      if (removed > 0) {
        LOG.infof("Reclaimed %d blob staging areas abandoned before this start", removed);
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not sweep abandoned blob staging at startup", e);
    }
  }

  /**
   * Streams {@code in} into a fresh staging area, computing its SHA-256 and size, aborting past
   * {@code capBytes} with a 413 (the staging is discarded on any failure).
   */
  public StagedBlob stage(InputStream in, long capBytes) {
    try (IncrementalStage staged = stageIncremental()) {
      staged.append(in, capBytes);
      return staged.finish();
    }
  }

  /**
   * Opens a blob that will be written across <b>more than one call</b>.
   *
   * <p>This exists for the OCI upload session, whose {@code PATCH} and {@code PUT} arrive as
   * separate HTTP requests — {@link #stage} cannot express that, because it consumes a whole stream
   * and returns a finished {@link StagedBlob}. Everything else is unchanged: the digest is still
   * computed while streaming and the cap still aborts mid-stream, so a gigabyte never materialises
   * in memory here either.
   */
  public IncrementalStage stageIncremental() {
    return new IncrementalStage(beginStaging());
  }

  /**
   * A staging area a writer can <b>read back</b> while filling it — git packs, the docs tar. See
   * {@link ScratchBlob}, which is the whole contract.
   */
  public ScratchBlob stageScratch() {
    return new ChunkScratchBlob(beginStaging());
  }

  /**
   * Throws away a staged blob that will not be promoted, and its chunks with it.
   *
   * <p>The counterpart of the temp-file unlink a caller used to do for itself. It is the store's
   * because the staging is: nothing outside this class can name a content id's rows. Safe on a
   * staging area that is already gone or already promoted — it touches {@code STAGING} rows only.
   */
  public void discard(StagedBlob staged) {
    openStages.remove(staged.contentId());
    discardStaging(staged.contentId());
  }

  /**
   * A blob being written incrementally. Feed it with {@link #append}, then {@link #finish} to get a
   * {@link StagedBlob} for {@link BlobStore#promote}.
   *
   * <p><b>Not thread-safe</b>, and deliberately so: one session, one writer. The running {@link
   * MessageDigest} is JVM state that cannot be persisted, so an unfinished stage does not survive a
   * restart — which is exactly the Distribution spec's session contract, where an upload id is
   * opaque and may expire at any time. Its chunks do survive, and the staging sweep is what
   * collects them.
   */
  public final class IncrementalStage implements Closeable {

    private final StagingChunks chunks;
    private final MessageDigest digest = sha256Digest();
    private long written;
    private boolean finished;

    private IncrementalStage(StagingChunks chunks) {
      this.chunks = chunks;
    }

    /** Bytes accepted so far — the offset a resumed upload must continue from. */
    public long written() {
      return written;
    }

    /**
     * Appends {@code in} to the running blob.
     *
     * @param capBytes the cap on the blob's <b>total</b> size, not on this call's contribution
     * @return the new total
     * @throws PayloadTooLargeException past the cap; the staging is discarded first
     */
    public long append(InputStream in, long capBytes) {
      try {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          written += n;
          if (written > capBytes) {
            throw new PayloadTooLargeException(
                "Upload exceeds the repository type's size cap of " + capBytes + " bytes");
          }
          digest.update(buf, 0, n);
          chunks.append(buf, 0, n);
        }
        return written;
      } catch (IOException e) {
        discard();
        throw new InternalServerErrorException("Failed to stage upload", e);
      } catch (RuntimeException e) {
        discard();
        throw e;
      }
    }

    /** Writes the last partial chunk and returns the finished stage, ready for {@link #promote}. */
    public StagedBlob finish() {
      try {
        chunks.finish();
      } catch (RuntimeException e) {
        discard();
        throw e;
      }
      finished = true;
      return new StagedBlob(
          HexFormat.of().formatHex(digest.digest()), written, chunks.contentId());
    }

    /** Drops the staged chunks. Idempotent, and a no-op after {@link #finish}. */
    public void discard() {
      if (finished) {
        return;
      }
      finished = true;
      BlobStore.this.openStages.remove(chunks.contentId());
      discardStaging(chunks.contentId());
    }

    /** {@code discard()} unless {@link #finish} already ran, so try-with-resources is always safe. */
    @Override
    public void close() {
      discard();
    }
  }

  /**
   * Binds a staged blob to its content address, or drops the staging if the content is already
   * stored. Moves no bytes either way.
   *
   * @return whether the bytes already existed (dedupe)
   */
  public boolean promote(StagedBlob staged) {
    if (!isValidId(staged.sha256())) {
      throw new InternalServerErrorException("Not a blob id: " + staged.sha256());
    }
    int writtenAt = chunkSize;
    boolean dedupe =
        db.inTransaction(
            "Failed to store blob " + staged.sha256(),
            connection -> {
              // Under the lock a sweep cannot remove between the existence check and the bind: the
              // race it closes is a probe answering "have it" for a blob about to go. Advisory and
              // per-blob, so it is correct across replicas, which the old in-process lock was not.
              lockBlob(connection, staged.sha256());
              boolean inserted;
              try (PreparedStatement insert =
                  connection.prepareStatement(
                      "insert into blob (id, content_id, size_bytes, chunk_size, stored_at)"
                          + " values (?, ?, ?, ?, now()) on conflict (id) do nothing")) {
                insert.setString(1, staged.sha256());
                insert.setObject(2, staged.contentId());
                insert.setLong(3, staged.size());
                insert.setInt(4, writtenAt);
                inserted = insert.executeUpdate() == 1;
              }
              // Inserted: the staged content becomes the stored content, untouched. Conflict: some
              // other content already holds this address, so the staged copy is redundant and the
              // cascade takes its chunks. stored_at is NOT refreshed on a dedupe — the same no-op
              // the old promote did when the file was already there.
              try (PreparedStatement settle =
                  connection.prepareStatement(
                      inserted
                          ? "update blob_content set state = 'PROMOTED' where content_id = ?"
                          : "delete from blob_content where content_id = ?")) {
                settle.setObject(1, staged.contentId());
                settle.executeUpdate();
              }
              return !inserted;
            });
    openStages.remove(staged.contentId());
    return dedupe;
  }

  /**
   * How a {@link #delete} ended. Every outcome is normal: a sweep plans against a census that may
   * already be stale, and refusing is the expected answer, not an error.
   */
  public enum DeleteResult {
    /** The blob and its bytes are gone. */
    DELETED,
    /** The blob still has a live reference. The census the plan was built from was stale. */
    STILL_REFERENCED,
    /** The blob is younger than the grace window. Not lost — the next run takes it. */
    WITHIN_GRACE_WINDOW,
    /** There is no such blob. Another sweep, or a hand, got there first. */
    ALREADY_GONE,
    /** Not a blob id at all. The shape defence, restated for the one path that removes. */
    NOT_A_BLOB_ID
  }

  /**
   * Answers, for one blob and at the last possible moment, whether nothing references it.
   *
   * <p>Must be a lookup, not a computation: it is called inside the store's short delete
   * transaction, holding that blob's advisory lock, so a guard that re-queried the world would
   * block writers for the length of a census. The sweep takes a fresh census, then passes a set
   * membership test.
   */
  @FunctionalInterface
  public interface SweepGuard {
    boolean stillUnreferenced(String blobId);
  }

  /**
   * Removes one blob. <b>The only way bytes leave this store, and the only callers are the sweeps
   * in the consuming services</b> — which reach it through {@link BlobReclaim}, the one narrow
   * public door. This method stays package-private, which is why nothing in a registry or a route
   * can reach it: a {@code public} delete would hand the constraints below to every package on the
   * classpath. The registries' {@code 405} on delete stays exactly as it is: GC is an internal
   * process, not an API, and no client gains deletion semantics from this.
   *
   * <p>Three constraints, each closing a way immutability could be broken by accident:
   *
   * <ul>
   *   <li><b>The grace window.</b> A blob younger than {@code qits.artifacts.gc.blob-grace-period}
   *       (default 7 days) is refused. The race it closes: a client's blob-exists probe — or npm's
   *       dedupe — answers "have it" for a blob this is about to remove, and the manifest that
   *       references it lands after. {@code stored_at} is when {@code promote} bound the address,
   *       so an upload in flight is orders of magnitude inside the window.
   *   <li><b>The pre-removal re-census.</b> A plan is a photograph; the store moves. {@code guard}
   *       is asked again here, under the advisory lock {@link #promote} also takes, so the check and
   *       the removal cannot be separated by a write.
   *   <li><b>Row-less blobs are never candidates.</b> This method cannot see identity rows and does
   *       not try: the rule is the caller's, and it is structural — a candidate must have
   *       <em>lost</em> its last identity row to a strategy's deletion, so a blob that never had one
   *       is unreachable from here.
   * </ul>
   */
  DeleteResult delete(String blobId, SweepGuard guard) {
    if (!isValidId(blobId)) {
      return DeleteResult.NOT_A_BLOB_ID;
    }
    return db.inTransaction(
        "Failed to delete blob " + blobId,
        connection -> {
          lockBlob(connection, blobId);
          UUID contentId;
          Instant storedAt;
          try (PreparedStatement select =
              connection.prepareStatement(
                  "select content_id, stored_at from blob where id = ? for update")) {
            select.setString(1, blobId);
            try (ResultSet found = select.executeQuery()) {
              if (!found.next()) {
                return DeleteResult.ALREADY_GONE;
              }
              contentId = found.getObject(1, UUID.class);
              storedAt = found.getObject(2, OffsetDateTime.class).toInstant();
            }
          }
          if (storedAt.isAfter(Instant.now().minus(blobGracePeriod))) {
            return DeleteResult.WITHIN_GRACE_WINDOW;
          }
          if (!guard.stillUnreferenced(blobId)) {
            return DeleteResult.STILL_REFERENCED;
          }
          // The identity first, then the content: the blob row is what points at the content, and
          // removing the content cascades to every chunk.
          try (PreparedStatement removeBlob =
              connection.prepareStatement("delete from blob where id = ?")) {
            removeBlob.setString(1, blobId);
            removeBlob.executeUpdate();
          }
          try (PreparedStatement removeContent =
              connection.prepareStatement("delete from blob_content where content_id = ?")) {
            removeContent.setObject(1, contentId);
            removeContent.executeUpdate();
          }
          return DeleteResult.DELETED;
        });
  }

  /** When this blob was bound to its address — {@code promote}'s stamp. Null if there is none. */
  Instant lastWrittenAt(String blobId) {
    Stored stored = find(blobId);
    return stored == null ? null : stored.storedAt();
  }

  /** The window {@link #delete} enforces, so a dry-run report can name what it withheld and why. */
  Duration blobGracePeriod() {
    return blobGracePeriod;
  }

  public boolean exists(String blobId) {
    return find(blobId) != null;
  }

  /**
   * Opens the content stream for serving. 404 on a malformed id (the shape defence) or a miss.
   *
   * <p>One autocommit {@code SELECT} per chunk as the reader pulls, so a slow client parks on its
   * socket and not on the connection pool.
   */
  public InputStream open(String blobId) {
    Stored stored = requireExisting(blobId);
    return new ChunkInputStream(seq -> BlobChunks.read(db, stored.contentId(), seq));
  }

  /**
   * Opens the blob for <b>random access</b> — seek and read, which is what serving a git clone out
   * of one large pack needs and what a stream cannot express. Same 404 gate as {@link #open}; the
   * channel is read-only and holds nothing between calls. See {@link BlobChunkChannel}.
   */
  public SeekableByteChannel openChannel(String blobId) {
    Stored stored = requireExisting(blobId);
    return new BlobChunkChannel(db, stored.contentId(), stored.size(), stored.chunkSize());
  }

  public long size(String blobId) {
    return requireExisting(blobId).size();
  }

  private Stored requireExisting(String blobId) {
    Stored stored = find(blobId);
    if (stored == null) {
      throw new NotFoundException("No such blob: " + blobId);
    }
    return stored;
  }

  private Stored find(String blobId) {
    if (!isValidId(blobId)) {
      return null;
    }
    return db.autocommit(
        "Failed to look up blob " + blobId,
        connection -> {
          try (PreparedStatement select =
              connection.prepareStatement(
                  "select content_id, size_bytes, chunk_size, stored_at from blob where id = ?")) {
            select.setString(1, blobId);
            try (ResultSet found = select.executeQuery()) {
              if (!found.next()) {
                return null;
              }
              return new Stored(
                  found.getObject(1, UUID.class),
                  found.getLong(2),
                  found.getInt(3),
                  found.getObject(4, OffsetDateTime.class).toInstant());
            }
          }
        });
  }

  /** A fresh {@code STAGING} content id, registered so the sweep leaves it alone. */
  private StagingChunks beginStaging() {
    UUID contentId = UUID.randomUUID();
    openStages.add(contentId);
    db.autocommit(
        "Failed to open blob staging",
        connection -> {
          try (PreparedStatement insert =
              connection.prepareStatement(
                  "insert into blob_content (content_id, state, started_at)"
                      + " values (?, 'STAGING', now())")) {
            insert.setObject(1, contentId);
            insert.executeUpdate();
          }
          return null;
        });
    return new StagingChunks(db, contentId, chunkSize);
  }

  /** Removes a staging area. Guarded on the state, so it can never touch promoted content. */
  private int discardStaging(UUID contentId) {
    return db.autocommit(
        "Failed to discard blob staging",
        connection -> {
          try (PreparedStatement delete =
              connection.prepareStatement(
                  "delete from blob_content where content_id = ? and state = 'STAGING'")) {
            delete.setObject(1, contentId);
            return delete.executeUpdate();
          }
        });
  }

  /**
   * Takes this blob's advisory lock for the rest of the transaction. Keyed on the content address,
   * so two different blobs never wait on each other, and released by the commit — there is no
   * unlock to forget.
   */
  private static void lockBlob(Connection connection, String blobId) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      lock.setString(1, blobId);
      lock.execute();
    }
  }

  static boolean isValidId(String blobId) {
    return blobId != null && SHA256_HEX.matcher(blobId).matches();
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new InternalServerErrorException("SHA-256 unavailable", e);
    }
  }

  /** {@link ScratchBlob} over a staging area: chunks in the database, one chunk in memory. */
  private final class ChunkScratchBlob implements ScratchBlob {

    private final StagingChunks chunks;
    private boolean sealed;
    private boolean closed;

    ChunkScratchBlob(StagingChunks chunks) {
      this.chunks = chunks;
    }

    @Override
    public UUID contentId() {
      return chunks.contentId();
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      if (sealed) {
        throw new IllegalStateException("this scratch blob was sealed by openRead()");
      }
      chunks.append(buf, off, len);
    }

    @Override
    public int read(long position, ByteBuffer dst) {
      long size = chunks.size();
      if (position < 0 || position >= size) {
        return -1;
      }
      long flushed = chunks.flushed();
      if (position >= flushed) {
        // Above the watermark: the bytes are only in this process's buffer, which is precisely why
        // a write-only stage cannot serve JGit.
        int offset = (int) (position - flushed);
        int taken = Math.min(dst.remaining(), chunks.buffered() - offset);
        dst.put(chunks.buffer(), offset, taken);
        return taken;
      }
      byte[] chunk = BlobChunks.read(db, chunks.contentId(), (int) (position / chunks.chunkSize()));
      if (chunk == null) {
        return -1;
      }
      int offset = (int) (position % chunks.chunkSize());
      int taken = Math.min(dst.remaining(), chunk.length - offset);
      dst.put(chunk, offset, taken);
      return taken;
    }

    @Override
    public InputStream openRead() {
      if (!sealed) {
        chunks.finish();
        sealed = true;
      }
      return new ChunkInputStream(seq -> BlobChunks.read(db, chunks.contentId(), seq));
    }

    @Override
    public long size() {
      return chunks.size();
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      // Guarded on STAGING: if promote adopted this content it is PROMOTED and untouched, and if
      // promote deduped it the row is already gone. Either way this is the abandoned case only.
      BlobStore.this.openStages.remove(chunks.contentId());
      discardStaging(chunks.contentId());
    }
  }
}
