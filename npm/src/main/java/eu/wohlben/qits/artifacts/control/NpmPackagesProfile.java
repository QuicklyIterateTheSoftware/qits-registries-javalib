package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A <b>hosted</b> npm registry, served at {@code /artifacts/npm/<repository>} by {@code
 * eu.wohlben.qits.npm}.
 *
 * <p>A protocol profile: a tarball arrives base64-inflated inside a publish document on a raw
 * Vert.x route and goes straight to {@code BlobStore}, so there is no media type to sniff and no
 * metadata to require, and the validating upload path is refused outright. The real cap is {@code
 * qits.artifacts.npm.max-publish-size}.
 *
 * <p>Versions are immutable — re-publishing one is {@code 403}, the npm analog of the registry's
 * append-only stance.
 */
@ApplicationScoped
public class NpmPackagesProfile implements RepositoryTypeProfile {

  public static final String KEY = "NPM_PACKAGES";

  @Override
  public String key() {
    return KEY;
  }
}
