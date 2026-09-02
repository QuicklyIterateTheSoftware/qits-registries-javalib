package eu.wohlben.qits.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The size cap on a proxied artifact, and — the half that cost an evening — what the refusal SAYS.
 *
 * <p>On 2026-09-01 the 201M playwright driver-bundle tripped the then-128M cap and the catch-all
 * reported "upstream maven repository is unreachable", sending everyone to the network while the
 * network was fine. The cap is policy and may refuse; the refusal must name the cap and its config
 * key, and must not be the 502 that means "upstream did not answer".
 *
 * <p>{@code max-artifact-size=1k} here for the same reason {@code MavenProxyMetadataTest} runs with
 * {@code metadata-ttl=PT0S}: proving the shipped 512M value would mean a half-gigabyte fixture.
 */
@QuarkusTest
@TestProfile(MavenProxyArtifactCapTest.TinyCap.class)
class MavenProxyArtifactCapTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = Long.toHexString(System.nanoTime());

  private static final String PROXY = "central";
  private static final String GROUP_PATH = "org/example";

  public static class TinyCap implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.artifacts.maven.proxy.upstream", StubMavenRepository.INSTANCE.baseUrl(),
          "qits.artifacts.maven.max-artifact-size", "1k");
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
  void anArtifactOverTheCapIs413NamingTheKeyAndNeverUnreachable() {
    String path = jarUpstream(new byte[2048]);

    try (MavenClient maven = client()) {
      HttpResponse<String> refused = maven.getText(PROXY, path);
      assertEquals(413, refused.statusCode(), refused.body());
      assertTrue(
          refused.body().contains("qits.artifacts.maven.max-artifact-size"),
          "the refusal must name the config key that raises the cap: " + refused.body());
      assertTrue(
          !refused.body().contains("unreachable"),
          "a size refusal blamed on the network is the misreport this test exists for");
    }
  }

  @Test
  void anArtifactUnderTheCapStillProxiesWhole() {
    byte[] content = new byte[512];
    content[0] = 42;
    String path = jarUpstream(content);

    try (MavenClient maven = client()) {
      HttpResponse<String> served = maven.getText(PROXY, path);
      assertEquals(200, served.statusCode());
      assertEquals(content.length, served.body().length());
    }
  }

  // --- fixture ----------------------------------------------------------------------------------

  /** Hosts one jar upstream and answers with the path it is served from. */
  private String jarUpstream(byte[] content) {
    String artifactId = "capped-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    StubMavenRepository.INSTANCE.hostFile(path, content);
    return path;
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
