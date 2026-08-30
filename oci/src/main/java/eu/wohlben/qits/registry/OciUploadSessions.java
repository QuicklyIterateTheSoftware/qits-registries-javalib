package eu.wohlben.qits.registry;

import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-flight blob uploads.
 *
 * <p>An OCI upload is three HTTP requests — {@code POST} to open, {@code PATCH} to stream, {@code
 * PUT} to finalize — so something has to hold the partially written blob between them. That is a
 * {@link BlobStore.IncrementalStage}: a staging area of chunk rows plus a running SHA-256.
 *
 * <p>Which means sessions are <b>in memory and die with the process</b>, and that is correct rather
 * than a limitation: a {@code MessageDigest} cannot be persisted, and the spec already says an
 * upload id is opaque and may expire at any time. A client meeting {@code BLOB_UPLOAD_UNKNOWN}
 * re-uploads the layer. This is why no session table exists in V2.
 *
 * <p>Cleanup is a lazy sweep when the next session opens, plus {@link #discardAll} at shutdown. A
 * scheduled sweeper would mean adding {@code quarkus-scheduler} — a new extension, and a
 * native-image conversation — for a twenty-line job: an idle registry opens no sessions and so leaks
 * nothing new, and a busy one sweeps on every push.
 */
@ApplicationScoped
public class OciUploadSessions {

  private static final Logger LOG = Logger.getLogger(OciUploadSessions.class);

  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  @Inject BlobStore blobStore;

  @ConfigProperty(name = "qits.artifacts.oci.upload-session-ttl", defaultValue = "PT30M")
  Duration ttl;

  /** One in-flight upload. Single-writer, like the stage it wraps. */
  public static final class Session {

    private final UUID id;
    private final BlobStore.IncrementalStage stage;
    private volatile Instant touched = Instant.now();

    private Session(UUID id, BlobStore.IncrementalStage stage) {
      this.id = id;
      this.stage = stage;
    }

    public UUID id() {
      return id;
    }

    /** Bytes accepted so far — the {@code Range} a client resumes from. */
    public long written() {
      return stage.written();
    }

    /** Appends a request body to the blob. */
    public long append(java.io.InputStream in, long capBytes) {
      touched = Instant.now();
      return stage.append(in, capBytes);
    }

    BlobStore.StagedBlob finish() {
      return stage.finish();
    }

    void discard() {
      stage.discard();
    }
  }

  /** Opens a session, sweeping anything that has expired first. */
  public Session open() {
    sweep();
    UUID id = UUID.randomUUID();
    Session session = new Session(id, blobStore.stageIncremental());
    sessions.put(id, session);
    return session;
  }

  /**
   * Resolves a session id from the wire.
   *
   * @throws OciException {@code BLOB_UPLOAD_UNKNOWN} for anything unknown, expired, malformed, or
   *     opened before a restart — all of which are the same thing to a client
   */
  public Session require(String id) {
    UUID parsed;
    try {
      parsed = UUID.fromString(id);
    } catch (IllegalArgumentException notAUuid) {
      throw unknown(id);
    }
    Session session = sessions.get(parsed);
    if (session == null) {
      throw unknown(id);
    }
    return session;
  }

  /** Finalizes a session: closes the stage, removes it, and returns the blob to promote. */
  public BlobStore.StagedBlob finish(Session session) {
    sessions.remove(session.id());
    return session.finish();
  }

  /** Abandons a session and drops its staged chunks. */
  public void cancel(Session session) {
    sessions.remove(session.id());
    session.discard();
  }

  private static OciException unknown(String id) {
    return new OciException(
        OciCode.BLOB_UPLOAD_UNKNOWN,
        "no such upload session; it may have expired or the registry may have restarted",
        Map.of("uuid", String.valueOf(id)));
  }

  private void sweep() {
    Instant deadline = Instant.now().minus(ttl);
    List.copyOf(sessions.values()).stream()
        .filter(session -> session.touched.isBefore(deadline))
        .forEach(
            session -> {
              LOG.debugf("discarding expired upload session %s", session.id());
              cancel(session);
            });
  }

  @PreDestroy
  void discardAll() {
    List.copyOf(sessions.values()).forEach(this::cancel);
  }
}
