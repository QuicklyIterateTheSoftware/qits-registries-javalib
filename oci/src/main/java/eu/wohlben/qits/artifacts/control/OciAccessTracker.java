package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import eu.wohlben.qits.blobstore.control.ArtifactAccessTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The OCI half of access tracking, coalesced on {@link ArtifactAccessTracker#WRITE_WINDOW}.
 *
 * <p>One class per format rather than the single {@code ArtifactAccessTracker} this was carved out
 * of — see {@code NpmAccessTracker} for why.
 */
@ApplicationScoped
public class OciAccessTracker {

  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;

  /** A tag pull touches both rows; a digest pull can only touch the manifest. */
  @Transactional
  public void touchManifest(
      String repository, String imageName, String digest, String tag, Instant now) {
    Instant cutoff = ArtifactAccessTracker.cutoff(now);
    manifests.touch(repository, imageName, digest, cutoff, now);
    if (tag != null) {
      tags.touch(repository, imageName, tag, cutoff, now);
    }
  }
}
