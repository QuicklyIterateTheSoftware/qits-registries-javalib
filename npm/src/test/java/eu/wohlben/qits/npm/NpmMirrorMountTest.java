package eu.wohlben.qits.npm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
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
 * The second npm mount, {@code qits.registries.npm.mirror-mount} — {@code MavenMirrorMountTest}'s
 * npm twin, with the one thing maven does not have: a packument names its tarballs by ABSOLUTE url,
 * so the mount a document was fetched on is the mount its tarballs must be on. The edge routes
 * {@code /artifacts} to the hosted registry on every vhost, so a document fetched through the edge
 * at {@code /mirror/npm} that named {@code /artifacts/npm} tarballs would resolve and then fail
 * every download.
 *
 * <p>Additive is the point, as for maven: {@code /artifacts/npm} keeps answering, and keeps naming
 * its own tarballs exactly as before. {@code NpmRegistryTest} runs without the extra mount and
 * proves the default is unchanged.
 */
@QuarkusTest
@TestProfile(NpmMirrorMountTest.WithMirrorMount.class)
class NpmMirrorMountTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = Long.toHexString(System.nanoTime());
  private static final String PROXY = "npmjs";

  public static class WithMirrorMount implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.artifacts.npm.proxy.upstream", StubNpmRegistry.INSTANCE.baseUrl(),
          "qits.registries.npm.mirror-mount", "/mirror/npm");
    }
  }

  @TestHTTPResource("/")
  URL root;

  @Inject ArtifactRepositoryService repositoryService;

  @BeforeEach
  void ensureRepositoryAndUpstream() {
    repositoryService.ensure(PROXY, NpmProxyProfile.KEY);
    StubNpmRegistry.INSTANCE.reset();
  }

  @Test
  void aPackumentOnTheMirrorMountNamesTarballsOnTheMirrorMountAndTheForwardedHost() {
    TinyPackage subject = upstream("mounted-" + RUN + "-" + UNIQUE.incrementAndGet());

    // What the edge sends: the vhost the client dialled, and the scheme it dialled it over.
    try (NpmClient viaEdge = throughTheEdge()) {
      HttpResponse<String> packument = viaEdge.packumentAt("mirror/npm", PROXY, subject.name());
      assertEquals(200, packument.statusCode(), packument.body());
      assertEquals(
          "https://mirror.example/mirror/npm/npmjs/"
              + subject.name()
              + "/-/"
              + subject.tarballFile(),
          NpmClient.tarballUrl(NpmClient.parse(packument.body()), "1.0.0"));
    }
  }

  @Test
  void theTarballDownloadsOnTheMirrorMountWithItsHeadTwin() {
    TinyPackage subject = upstream("fetched-" + RUN + "-" + UNIQUE.incrementAndGet());

    try (NpmClient npm = client()) {
      JsonNode packument = NpmClient.parse(npm.packumentAt("mirror/npm", PROXY, subject.name()).body());
      String tarballUrl = NpmClient.tarballUrl(packument, "1.0.0");
      assertTrue(
          tarballUrl.startsWith(root + "mirror/npm/npmjs/"),
          "an in-network request on the mirror mount names its own authority; got " + tarballUrl);

      assertEquals(200, npm.head(tarballUrl).statusCode());
      HttpResponse<byte[]> tarball = npm.tarball(tarballUrl);
      assertEquals(200, tarball.statusCode());
      assertArrayEquals(subject.tarball(), tarball.body());
    }
  }

  @Test
  void aScopedPackageResolvesAndDownloadsOnTheMirrorMount() {
    TinyPackage subject = upstream("@mirrored/pkg-" + RUN + "-" + UNIQUE.incrementAndGet());

    try (NpmClient viaEdge = throughTheEdge(); NpmClient npm = client()) {
      // npm sends the scope separator encoded for a packument, and follows the tarball url verbatim.
      HttpResponse<String> packument =
          viaEdge.packumentAt("mirror/npm", PROXY, subject.name().replace("/", "%2f"));
      assertEquals(200, packument.statusCode(), packument.body());
      String tarballUrl = NpmClient.tarballUrl(NpmClient.parse(packument.body()), "1.0.0");
      assertEquals(
          "https://mirror.example/mirror/npm/npmjs/"
              + subject.name()
              + "/-/"
              + subject.tarballFile(),
          tarballUrl);

      // The same path, dialled on this process instead of the pretended public host.
      HttpResponse<byte[]> tarball =
          npm.tarball(tarballUrl.replace("https://mirror.example/", root.toString()));
      assertEquals(200, tarball.statusCode());
      assertArrayEquals(subject.tarball(), tarball.body());
    }
  }

  @Test
  void theArtifactsMountKeepsItsOwnTarballUrlsWithOrWithoutForwardedHeaders() {
    TinyPackage subject = upstream("unmoved-" + RUN + "-" + UNIQUE.incrementAndGet());
    String tail = "/artifacts/npm/npmjs/" + subject.name() + "/-/" + subject.tarballFile();

    // In-network: no forwarding hop, so the authority this process was dialled on.
    try (NpmClient npm = client()) {
      JsonNode packument = npm.packumentJson(PROXY, subject.name());
      assertEquals(
          root.toString().replaceAll("/$", "") + tail, NpmClient.tarballUrl(packument, "1.0.0"));
    }
    // Forwarded: the client's host, and still the mount the request came in on.
    try (NpmClient viaEdge = throughTheEdge()) {
      JsonNode packument = viaEdge.packumentJson(PROXY, subject.name());
      assertEquals("https://mirror.example" + tail, NpmClient.tarballUrl(packument, "1.0.0"));
    }
  }

  @Test
  void anUnknownPathUnderTheMirrorMountIsTheRegistrysJson404() {
    try (NpmClient npm = client()) {
      HttpResponse<String> miss = npm.get("mirror/npm/npmjs/-/v1/search?text=left-pad");
      assertEquals(404, miss.statusCode());
      assertTrue(miss.body().contains("\"error\""), "npm's error shape, not a page; got " + miss.body());
    }
  }

  private TinyPackage upstream(String name) {
    TinyPackage subject = TinyPackage.of(name, "1.0.0");
    StubNpmRegistry.INSTANCE.hostPackage(subject);
    return subject;
  }

  private NpmClient throughTheEdge() {
    return client().header("X-Forwarded-Host", "mirror.example").header("X-Forwarded-Proto", "https");
  }

  private NpmClient client() {
    return new NpmClient(URI.create(root.toString()));
  }
}
