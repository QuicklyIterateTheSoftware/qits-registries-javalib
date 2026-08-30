package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.blobstore.control.ArtifactAccessTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The maven half of access tracking, coalesced on {@link ArtifactAccessTracker#WRITE_WINDOW}.
 *
 * <p>One class per format rather than the single {@code ArtifactAccessTracker} this was carved out
 * of — see {@code NpmAccessTracker} for why.
 */
@ApplicationScoped
public class MavenAccessTracker {

  @Inject MavenArtifactRepository mavenArtifacts;

  /** One deployed maven path. The derived documents and checksums are not this row's bytes. */
  @Transactional
  public void touchMavenArtifact(String repository, String path, Instant now) {
    mavenArtifacts.touch(repository, path, ArtifactAccessTracker.cutoff(now), now);
  }
}
