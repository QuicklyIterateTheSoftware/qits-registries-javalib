package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;

/**
 * Repository-scoped, one-hour-coalesced access writes for cleanup's reachability roots.
 *
 * <p><b>The store's own half only.</b> In qits-platform-artifacts this class also touched the npm,
 * maven, OCI, daemon and docs identity rows. Those tables are not this lib's, so the format halves
 * travel with their formats — {@code NpmAccessTracker}, {@code MavenAccessTracker} and {@code
 * OciAccessTracker} in qits-registries, and the daemon/docs pair with whichever service keeps them.
 * They all coalesce on {@link #WRITE_WINDOW} and {@link #cutoff(Instant)} here, so one window
 * governs every type as it did when this was one class.
 */
@ApplicationScoped
public class ArtifactAccessTracker {

  /** How long a row's recorded access stands before another read rewrites it. */
  public static final Duration WRITE_WINDOW = Duration.ofHours(1);

  @Inject ArtifactRecordRepository records;

  @Transactional
  public void touchArtifact(String repository, String blobId, Instant now) {
    records.touchByRepositoryAndBlob(repository, blobId, cutoff(now), now);
  }

  /** The coalescing floor: a row whose access is newer than this is left alone. */
  public static Instant cutoff(Instant now) {
    return now.minus(WRITE_WINDOW);
  }
}
