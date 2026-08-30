package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A <b>hosted</b> maven repository, served at {@code /artifacts/maven/<repository>} by {@code
 * eu.wohlben.qits.maven}.
 *
 * <p>A protocol profile: a jar or a pom arrives on the maven wire routes and goes straight to
 * {@code BlobStore}, so there is no media type to sniff and no metadata to require, and the
 * validating upload path is refused outright. The real cap is {@code
 * qits.artifacts.maven.max-artifact-size}.
 *
 * <p>Release paths are immutable — a re-deploy with different bytes is {@code 403}, the maven analog
 * of the registry's append-only stance. A re-deploy of <em>identical</em> bytes is an idempotent
 * no-op, which content addressing makes free; timestamped snapshot files are unique by construction
 * and take the release rule, and a literal {@code -SNAPSHOT} filename is the one mutable path
 * (maven-repository-plan.md §3.6).
 */
@ApplicationScoped
public class MavenPackagesProfile implements RepositoryTypeProfile {

  public static final String KEY = "MAVEN_PACKAGES";

  @Override
  public String key() {
    return KEY;
  }
}
