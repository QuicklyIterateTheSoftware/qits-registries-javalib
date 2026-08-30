package eu.wohlben.qits.blobstore.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * Golden screenshots, diffed by branch — one of the two profiles the core ships itself.
 *
 * <p>The CI types serve the golden-diff loop, and they are the reason the validating upload path
 * exists at all. The required keys are the pairing/comparison keys the diff UI needs (branch,
 * commit, flow name + hash, display name, diff hash) plus the media resolution this type can
 * express, which {@code BlobService} additionally checks against the PNG's own IHDR.
 */
@ApplicationScoped
public class CiScreenshotsProfile implements RepositoryTypeProfile {

  public static final String KEY = "CI_SCREENSHOTS";

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public boolean allowsValidatedUploads() {
    return true;
  }

  @Override
  public Set<String> allowedMediaTypes() {
    return Set.of("image/png", "image/jpeg", "image/svg+xml");
  }

  @Override
  public Set<String> requiredMetadataKeys() {
    return Set.of(
        MetadataKeys.GIT_BRANCH_NAME,
        MetadataKeys.GIT_COMMIT_HASH,
        MetadataKeys.USERFLOW_NAME,
        "qits.userflow.hash",
        "qits.display.name",
        "qits.diff.hash",
        MetadataKeys.RESOLUTION_WIDTH,
        MetadataKeys.RESOLUTION_HEIGHT);
  }

  @Override
  public long maxBytes() {
    return 25L * 1024 * 1024;
  }
}
