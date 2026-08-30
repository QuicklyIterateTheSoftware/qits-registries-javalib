package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A <b>pull-through cache</b> of an upstream npm registry (default {@code
 * https://registry.npmjs.org}), served on the same routes as {@link NpmPackagesProfile}.
 *
 * <p>Separate from the hosted type rather than a flag on it, following the namespacing rule the OCI
 * mirror already settled: cached upstream content and published content must not share a namespace,
 * and a cache must reject publishes <em>by type</em> rather than by configuration. So a {@code PUT}
 * here is refused because of what this type is, not because of how a deployment set it up, and no
 * repository can drift from one meaning to the other — {@code ArtifactRepositoryService.ensure}
 * makes a repository's type immutable.
 */
@ApplicationScoped
public class NpmProxyProfile implements RepositoryTypeProfile {

  public static final String KEY = "NPM_PROXY";

  @Override
  public String key() {
    return KEY;
  }
}
