package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.blobstore.control.ArtifactAccessTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The npm half of access tracking, coalesced on {@link ArtifactAccessTracker#WRITE_WINDOW}.
 *
 * <p>One class per format rather than the single {@code ArtifactAccessTracker} this was carved out
 * of: that class injected every format's tables, which is a dependency the blob store cannot have
 * and no one format module can satisfy. The window and its cutoff still come from the store, so all
 * types coalesce identically.
 */
@ApplicationScoped
public class NpmAccessTracker {

  @Inject NpmVersionRepository npmVersions;

  /**
   * One npm version, hosted or proxied — {@code npm_version} is one table for both types, so this is
   * one method and the tarball route stays one code path. The proxy's packument row is untouched:
   * its {@code fetched_at} answers "when was the document last revalidated", which is a different
   * question from "when were these bytes last wanted".
   */
  @Transactional
  public void touchNpmVersion(String repository, String packageName, String version, Instant now) {
    npmVersions.touch(repository, packageName, version, ArtifactAccessTracker.cutoff(now), now);
  }
}
