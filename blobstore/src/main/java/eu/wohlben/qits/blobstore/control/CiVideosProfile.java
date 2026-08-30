package eu.wohlben.qits.blobstore.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/** Golden videos, diffed by branch — the second profile the core ships itself. */
@ApplicationScoped
public class CiVideosProfile implements RepositoryTypeProfile {

  public static final String KEY = "CI_VIDEOS";

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
    return Set.of("video/mp4", "video/webm");
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
        "media.resolution.length");
  }

  @Override
  public long maxBytes() {
    // 64 MB: generous for a short compressed golden clip, matched to the global HTTP body ceiling
    // (see service application.properties + docs/issues on the max-body-size tradeoff).
    return 64L * 1024 * 1024;
  }
}
