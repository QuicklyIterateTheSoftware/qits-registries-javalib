package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A <b>pull-through cache</b> of an upstream container registry, served on the same {@code /v2}
 * routes as {@link OciImagesProfile}.
 *
 * <p>A protocol profile on that pattern, and everything its javadoc says holds verbatim — a mirrored
 * layer arrives on the registry's own wire routes and goes straight to {@code BlobStore}. The real
 * cap is {@code qits.artifacts.oci.max-layer-size}, the same one the hosted type is bounded by.
 *
 * <p>One row per registered upstream, named by that upstream's local namespace segment: {@code hub},
 * {@code quay}, {@code redhat}, paired with an {@code oci_mirror_upstream} row carrying the domain
 * it fronts. So {@code docker pull <host>/quay/quarkus/ubi9-…:jdk-25} reads like what it is, and
 * every future per-upstream property (a credential, a per-upstream TTL) has a row to hang on. Cached
 * content lives in ordinary {@code oci_manifest}/{@code oci_tag} rows under the slug.
 *
 * <p><b>A push here is refused by type</b>, exactly as npm's proxy refuses a publish: cached upstream
 * content and pushed content must never share a namespace, and no repository can drift from one
 * meaning to the other because {@code ArtifactRepositoryService.ensure} makes a type immutable. The
 * refusal is {@code 405}, not a configuration.
 *
 * <p>Garbage collection <b>evicts</b> what nothing has pulled inside the configured window ({@code
 * OciMirrorGcStrategy} on the cache engine). The separate type is what keeps that decision from
 * distorting {@link OciImagesProfile}'s rules — a mirror tag like {@code jdk-25} is neither a calver
 * release nor a build sha, and would otherwise be kept by docker's unclassified-means-keep rule and
 * reported as if somebody had decided something. Upstream's releases earn no protection here:
 * version protection is own-ness's, and a cache holds none of ours.
 */
@ApplicationScoped
public class OciMirrorProfile implements RepositoryTypeProfile {

  public static final String KEY = "OCI_MIRROR";

  @Override
  public String key() {
    return KEY;
  }
}
