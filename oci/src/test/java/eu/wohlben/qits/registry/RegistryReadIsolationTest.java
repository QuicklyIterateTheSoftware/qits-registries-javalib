package eu.wohlben.qits.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * One slow blob pull must not stall the reads behind it (qits-1510).
 *
 * <p>The bug was an ordering, not a slowness: every route was an <b>ordered</b> {@code
 * blockingHandler}, and ordered tasks of one event loop run one at a time from a queue every request
 * on that loop shares. {@link BlobSender} holds its worker for the whole transfer, parked on the
 * client's drain, so a manifest HEAD queued behind a blob GET to a slow puller waited out the
 * transfer — up to {@code qits.artifacts.blob-send-drain-timeout} per stall.
 *
 * <p>The collision is made deterministic rather than hoped for: the profile pins the server to ONE
 * event loop, so the stalled GET and the HEAD are guaranteed to share it, and the puller is a raw
 * socket with a tiny receive window that sends its request and then never reads — the slowest
 * client there is. With reads ordered, the HEAD sits behind the parked GET and times out; unordered,
 * it is answered at once.
 *
 * <p>Its own class because it needs a {@code @TestProfile}, which forces a Quarkus restart.
 */
@QuarkusTest
@TestProfile(RegistryReadIsolationTest.OneEventLoop.class)
class RegistryReadIsolationTest {

  /**
   * One event loop, so every connection lands on the same one and shares its ordered queue. The
   * chunk size goes back up to something real only so pushing the large blob stays quick.
   */
  public static class OneEventLoop implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.vertx.event-loops-pool-size", "1",
          "quarkus.http.io-threads", "1",
          "qits.artifacts.blob-chunk-size", "1048576");
    }
  }

  private static final String IMAGE = "qits/read-isolation";

  /**
   * Far more than a never-reading client's receive window, the kernel's send buffer and Netty's
   * write-queue watermark together can absorb, so the sender is certain to park on drain.
   */
  private static final int LARGE_BLOB = 16 * 1024 * 1024;

  @TestHTTPResource("/")
  URL root;

  @Inject ArtifactRepositoryService repositoryService;

  @Test
  void aManifestHeadIsNotQueuedBehindAStalledBlobPull() throws Exception {
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    URI base = URI.create(root.toString());

    byte[] large = new byte[LARGE_BLOB];
    new Random(1510).nextBytes(large);
    String largeDigest = TinyImage.digest(large);
    try (OciClient client = new OciClient(base)) {
      assertEquals(201, client.monolithicUpload(IMAGE, largeDigest, large).statusCode());
      client.push(IMAGE, "latest", TinyImage.of("read-isolation"));
    }

    try (Socket puller = new Socket()) {
      // Set before connecting, so the advertised window is small from the first segment.
      puller.setReceiveBufferSize(4096);
      puller.connect(new InetSocketAddress(base.getHost(), base.getPort()), 5_000);
      OutputStream out = puller.getOutputStream();
      out.write(
          ("GET /v2/" + IMAGE + "/blobs/" + largeDigest + " HTTP/1.1\r\n"
                  + "Host: " + base.getHost() + ":" + base.getPort() + "\r\n"
                  + "\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      // Never read. Give the sender time to fill every buffer between it and this socket and park.
      Thread.sleep(1_500);

      try (HttpClient http =
          HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
        HttpRequest head =
            HttpRequest.newBuilder(base.resolve("/v2/" + IMAGE + "/manifests/latest"))
                .header("Accept", TinyImage.MANIFEST_TYPE)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        long started = System.nanoTime();
        HttpResponse<Void> response;
        try {
          response = http.send(head, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException e) {
          fail("the manifest HEAD was queued behind the stalled blob pull and never answered");
          return;
        }
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(200, response.statusCode());
        assertTrue(
            tookMillis < 1_000,
            "the manifest HEAD took " + tookMillis + " ms behind a stalled blob pull");
      }
    }
  }
}
