package eu.wohlben.qits.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What happens to a cached packument once its TTL is up — the half {@code NpmProxyTest} cannot
 * reach, because proving expiry against the shipped five minutes would mean a five-minute test.
 *
 * <p>{@code packument-ttl=PT0S} makes every request expired on arrival, which turns three distinct
 * claims into fast assertions: expiry <b>revalidates</b> rather than refetches, a new upstream
 * version becomes visible, and an unreachable upstream serves the stale copy instead of failing.
 * That last one is half of why the proxy exists at all — an npmjs outage must not stop CI.
 */
@QuarkusTest
@TestProfile(NpmProxyRevalidationTest.ExpiredOnArrival.class)
class NpmProxyRevalidationTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  public static class ExpiredOnArrival implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.artifacts.npm.proxy.upstream", StubNpmRegistry.INSTANCE.baseUrl(),
          "qits.artifacts.npm.proxy.packument-ttl", "PT0S");
    }
  }

  @TestHTTPResource("/")
  URL root;

  /**
   * The repository row, made through the service rather than the admin endpoint the monolith's copy
   * of this suite used: the JSON admin surface is a service's, not this lib's.
   */
  @Inject ArtifactRepositoryService repositoryService;

  @BeforeEach
  void ensureRepositoryAndUpstream() {
    repositoryService.ensure("npmjs", NpmProxyProfile.KEY);
    StubNpmRegistry.INSTANCE.reset();
  }

  @Test
  void anExpiredPackumentIsRevalidatedRatherThanRefetched() {
    TinyPackage subject = upstream("2.0.0");

    try (NpmClient npm = client()) {
      npm.packumentJson("npmjs", subject.name());
      assertEquals(0, StubNpmRegistry.INSTANCE.conditionalRequests(), "nothing to validate yet");

      JsonNode again = npm.packumentJson("npmjs", subject.name());
      assertEquals("2.0.0", again.path("dist-tags").path("latest").asText());
      assertEquals(
          1,
          StubNpmRegistry.INSTANCE.conditionalRequests(),
          "the second request must carry If-None-Match from the stored ETag");
    }
  }

  @Test
  void aNewUpstreamVersionAppearsOnceTheTtlHasPassed() {
    // The reason a packument has a TTL at all and a tarball does not: this document mutates.
    TinyPackage first = upstream("1.0.0");
    try (NpmClient npm = client()) {
      assertEquals("1.0.0", npm.packumentJson("npmjs", first.name()).path("dist-tags").path("latest").asText());

      TinyPackage second = TinyPackage.of(first.name(), "1.1.0");
      StubNpmRegistry.INSTANCE.hostVersions(first.name(), first, second);

      JsonNode refreshed = npm.packumentJson("npmjs", first.name());
      assertEquals("1.1.0", refreshed.path("dist-tags").path("latest").asText());
      assertTrue(refreshed.path("versions").has("1.0.0"), "the old version stays installable");
    }
  }

  @Test
  void anUnreachableUpstreamServesTheStaleCopy() {
    TinyPackage subject = upstream("1.0.0");

    try (NpmClient npm = client()) {
      assertEquals(200, npm.packument("npmjs", subject.name()).statusCode());

      StubNpmRegistry.INSTANCE.reachable(false);
      JsonNode stale = npm.packumentJson("npmjs", subject.name());
      assertEquals(
          "1.0.0",
          stale.path("dist-tags").path("latest").asText(),
          "an npmjs outage must not stop an install of something already seen");
      // and the tarball urls in it still point here, so the install can actually proceed
      assertTrue(NpmClient.tarballUrl(stale, "1.0.0").contains("/artifacts/npm/npmjs/"));
    }
  }

  @Test
  void anUnreachableUpstreamAndNothingCachedIsA502() {
    StubNpmRegistry.INSTANCE.reachable(false);
    try (NpmClient npm = client()) {
      assertEquals(502, npm.packument("npmjs", "never-seen-" + UNIQUE.incrementAndGet()).statusCode());
    }
  }

  private TinyPackage upstream(String version) {
    TinyPackage subject = TinyPackage.of("revalidated-" + UNIQUE.incrementAndGet(), version);
    StubNpmRegistry.INSTANCE.hostPackage(subject);
    return subject;
  }

  private NpmClient client() {
    return new NpmClient(URI.create(root.toString()));
  }
}
