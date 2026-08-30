package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A <b>pull-through cache</b> of an upstream maven repository (default Maven Central, {@code
 * https://repo1.maven.org/maven2}), served on the same routes as {@link MavenPackagesProfile}.
 *
 * <p>Separate from the hosted type rather than a flag on it, following the rule npm's proxy and the
 * OCI mirror already settled: cached upstream content and published content must not share a
 * namespace, and a cache must reject writes <em>by type</em> rather than by configuration. So a
 * {@code PUT} here is {@code 405} because of what this type is, and no repository can drift from one
 * meaning to the other — {@code ArtifactRepositoryService.ensure} makes a type immutable.
 *
 * <p>Cached files live in ordinary {@code maven_artifact} rows under the proxy's own repository
 * name, so the census, the explorer and the blob store need no new code — a cached jar is a deployed
 * jar to all three, told apart by its repository's type. The one thing that needs a table of its own
 * is {@code maven-metadata.xml}: it is the only maven document that mutates, so it is cached with a
 * TTL in {@code maven_proxy_metadata} rather than being an immutable path.
 *
 * <p>Garbage collection <b>evicts</b> what nothing has resolved inside the configured window
 * ({@code MavenProxyGcStrategy} on the cache engine). Upstream's releases earn no protection here:
 * version protection is own-ness's, and a cache holds none of ours.
 */
@ApplicationScoped
public class MavenProxyProfile implements RepositoryTypeProfile {

  public static final String KEY = "MAVEN_PROXY";

  @Override
  public String key() {
    return KEY;
  }
}
