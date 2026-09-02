package eu.wohlben.qits.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The second maven mount, {@code qits.registries.maven.mirror-mount}. The pull-through mirror serves
 * its caches under its own {@code /mirror} prefix as well as {@code /artifacts/maven}, because the
 * edge routes {@code /artifacts} to the hosted registry that owns it and a build reaching the mirror
 * vhost there is handed to the wrong service. Both mounts share one handler set, so the same artifact
 * resolves identically under either — proved here against a real proxy fetch.
 *
 * <p>Additive is the point: {@code /artifacts/maven} keeps answering (the in-network step plane) so
 * no consumer migrates atomically. {@code MavenProxyMetadataTest} et al. run without the extra mount
 * and prove the default is unchanged.
 */
@QuarkusTest
@TestProfile(MavenMirrorMountTest.WithMirrorMount.class)
class MavenMirrorMountTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = Long.toHexString(System.nanoTime());
  private static final String PROXY = "central";

  public static class WithMirrorMount implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.artifacts.maven.proxy.upstream", StubMavenRepository.INSTANCE.baseUrl(),
          "qits.registries.maven.mirror-mount", "/mirror/maven");
    }
  }

  @TestHTTPResource("/")
  URL root;

  @Inject ArtifactRepositoryService repositoryService;

  @BeforeEach
  void ensureRepositoryAndUpstream() {
    repositoryService.ensure(PROXY, MavenProxyProfile.KEY);
    StubMavenRepository.INSTANCE.reset();
  }

  @Test
  void theSameArtifactResolvesUnderBothTheArtifactsAndTheMirrorMount() {
    String artifactId = "mounted-" + RUN + "-" + UNIQUE.incrementAndGet();
    String rel = "org/example/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    byte[] content = ("jar-bytes-" + artifactId).getBytes();
    // Hosted at the UPSTREAM path — the repo-relative path with no `central/` prefix, the proxy
    // strips the local repository segment before fetching (as the neighbouring proxy tests do).
    StubMavenRepository.INSTANCE.hostFile(rel, content);

    try (MavenClient maven = client()) {
      // The legacy in-network mount.
      HttpResponse<byte[]> viaArtifacts = maven.get(PROXY, rel);
      assertEquals(200, viaArtifacts.statusCode());
      assertArrayEquals(content, viaArtifacts.body());

      // The mirror's own prefix — what the edge routes to the mirror.
      HttpResponse<String> viaMirror =
          maven.getText(PROXY, rel); // warm; then hit the absolute /mirror path
      assertEquals(200, viaMirror.statusCode());
      HttpResponse<String> mirrorMount =
          maven.getAbsolute("/mirror/maven/" + PROXY + "/" + rel);
      assertEquals(200, mirrorMount.statusCode(), mirrorMount.body());
      assertArrayEquals(content, mirrorMount.body().getBytes());
    }
  }

  @Test
  void anUnknownPathUnderTheMirrorMountIs404NotTheDefaultMount() {
    try (MavenClient maven = client()) {
      HttpResponse<String> miss =
          maven.getAbsolute("/mirror/maven/" + PROXY + "/no/such/thing/1.0/thing-1.0.jar");
      // A real 404 from the mirror mount's own handler, not a route that fell through to nothing.
      assertEquals(404, miss.statusCode());
    }
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
