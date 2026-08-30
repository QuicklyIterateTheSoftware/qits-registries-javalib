package eu.wohlben.qits.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The layer cap — the security half of raising the wire ceiling, and the half {@code
 * quarkus.http.limits.max-body-size} does not cover.
 *
 * <p>Quarkus' ceiling compares a declared {@code Content-Length} and, when there is none, only
 * stashes the limit for a body reader to apply ({@code BodyCeilingProbeTest} pins that). A layer
 * arrives chunked, so what actually bounds it is this cap, enforced by {@code BlobStore} while
 * streaming — the same mechanism {@code RepositoryTypeProfile.maxBytes()} uses for the CI types, and
 * untouched by the ceiling raise.
 *
 * <p>Its own class because it needs a {@code @TestProfile}, which forces a Quarkus restart. Note the
 * known {@code Port already bound: 8081} flake around restarts — re-run before investigating.
 */
@QuarkusTest
@TestProfile(OciLayerCapTest.TinyLayerCap.class)
class OciLayerCapTest {

  /** A cap far below anything real, so the test body stays small and the suite stays fast. */
  public static class TinyLayerCap implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.oci.max-layer-size", "4K");
    }
  }

  private static final String IMAGE = "qits/capped";

  @TestHTTPResource("/")
  URL root;

  /** The repository row: the JSON admin endpoint is a service's, so the row comes from here. */
  @Inject ArtifactRepositoryService repositoryService;

  @BeforeEach
  void ensureRepository() {
    repositoryService.ensure("qits", OciImagesProfile.KEY);
  }

  @Test
  void aChunkedUploadPastTheCapIsRejectedAndStoresNothing() {
    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      byte[] tooBig = new byte[16 * 1024];
      String digest = TinyImage.digest(tooBig);

      String session = client.startUpload(IMAGE);
      HttpResponse<String> response = client.patchUpload(session, tooBig);

      assertEquals(413, response.statusCode(), response.body());
      assertTrue(response.body().contains("SIZE_INVALID"), response.body());
      assertFalse(client.blobExists(IMAGE, digest), "a rejected layer must not be stored");
    }
  }

  @Test
  void aLayerUnderTheCapStillGoesThrough() {
    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      byte[] small = new byte[1024];
      String digest = TinyImage.digest(small);
      assertEquals(201, client.monolithicUpload(IMAGE, digest, small).statusCode());
      assertTrue(client.blobExists(IMAGE, digest));
    }
  }
}
