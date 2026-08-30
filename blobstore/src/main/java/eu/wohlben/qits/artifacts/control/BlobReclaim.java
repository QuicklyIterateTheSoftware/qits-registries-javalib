package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;

/**
 * The blob store's removal door, opened exactly wide enough for a garbage collector and no wider.
 *
 * <p><b>A narrow facade rather than a widened method.</b> {@link BlobStore#delete} stays
 * package-private for the reason {@code promote} is the one write funnel: the grace window and the
 * pre-removal guard taken under the store's per-blob advisory lock only hold while there is one way
 * in. Making it {@code public} would remove that guarantee for every package on the classpath at
 * once — every route, every registry, every future caller — to serve one module. This class is the
 * alternative the repository already uses where a seam has to cross a jar boundary: a named door
 * with a documented owner, delegating to the funnel rather than replacing it.
 *
 * <p><b>The owner is a consuming service's garbage collector and nothing else</b> — one sweep class
 * for the removal, that sweep and its executor for the two clock reads. A second caller appearing anywhere is the signal that this door has become an API, which it is
 * not — the registries' {@code 405} on client deletes stays exactly as it is, and no client gains
 * deletion semantics from any of this.
 *
 * <p>Nothing here adds a rule or removes one. Every constraint {@link BlobStore#delete} documents
 * is enforced by {@link BlobStore#delete}, on the far side of this call.
 */
@ApplicationScoped
public class BlobReclaim {

  @Inject BlobStore blobStore;

  /**
   * Removes one blob, through the store's own funnel. See {@link BlobStore#delete} for the three
   * constraints it enforces; every outcome is a normal answer rather than an exception.
   */
  public BlobStore.DeleteResult delete(String blobId, BlobStore.SweepGuard guard) {
    return blobStore.delete(blobId, guard);
  }

  /** When this blob was bound to its address — {@code promote}'s stamp. Null if there is none. */
  public Instant lastWrittenAt(String blobId) {
    return blobStore.lastWrittenAt(blobId);
  }

  /**
   * The window {@link BlobStore#delete} enforces, so a plan can withhold identities on the same
   * clock the removal will be judged against — and a report can name what it withheld and why.
   */
  public Duration graceWindow() {
    return blobStore.blobGracePeriod();
  }
}
