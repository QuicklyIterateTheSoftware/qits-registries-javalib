package eu.wohlben.qits.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.MavenProxyProfile;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
 * A cached entry that cannot be served throws itself away and fetches again, inside the same
 * request.
 *
 * <p><b>The incident this is written from.</b> On 2026-09-05 a cached {@code
 * io/quarkus/quarkus-proxy-registry/3.34.6/quarkus-proxy-registry-3.34.6.pom} answered {@code 500}
 * to every request for four days. The bytes were fine, upstream was fine and the adjacent version
 * was fine; the row itself had gone bad, and the access-tracking {@code UPDATE} that every read
 * performs raised {@code duplicate key value violates unique constraint "maven_artifact_pkey"} — an
 * {@code UPDATE} that touches no key column, so the primary key had stopped agreeing with the heap.
 * Nothing could clear it: the eviction window is ninety days and the sweep is a clock, not a hand.
 * Meanwhile it blocked release gates across the platform.
 *
 * <p>The lesson is not about that one fault. It is that a <b>pull-through cache holds nothing it
 * cannot get again</b>, so an entry it cannot serve is worth exactly nothing — and keeping it while
 * refusing every request for it is the one behaviour that has no argument for it. Every other cache
 * failure mode this module handles already follows that rule: a stale document is revalidated, a
 * miss is fetched. A broken row was the gap.
 *
 * <h2>What is simulated, and why it is the honest simulation</h2>
 *
 * <p>The row is pointed at a blob id that is well-formed and absent. That is the same CLASS of fault
 * as the one above — the row is present and its bytes cannot be produced — reached through the same
 * code path ({@code serveStored}'s preamble, before a byte of the response is written) and it is
 * reachable from a test, which the duplicate-tuple state is not. What matters is what the route does
 * when the preamble fails, and that is identical for both.
 */
@QuarkusTest
@TestProfile(MavenProxySelfHealTest.AgainstTheStub.class)
class MavenProxySelfHealTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = Long.toHexString(System.nanoTime());

  private static final String PROXY = "central";
  private static final String HOSTED = "maven";
  private static final String GROUP_PATH = "org/example";

  /** Well-formed and stored nowhere: sixty-four hex characters the blob table has never seen. */
  private static final String ABSENT_BLOB = "de".repeat(32);

  public static class AgainstTheStub implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.maven.proxy.upstream", StubMavenRepository.INSTANCE.baseUrl());
    }
  }

  @TestHTTPResource("/")
  URL root;

  @Inject ArtifactRepositoryService repositoryService;
  @Inject MavenArtifactRepository artifacts;
  @Inject eu.wohlben.qits.artifacts.control.MavenRegistryService registry;

  @BeforeEach
  void ensureRepositoriesAndUpstream() {
    repositoryService.ensure(PROXY, MavenProxyProfile.KEY);
    repositoryService.ensure(HOSTED, MavenPackagesProfile.KEY);
    StubMavenRepository.INSTANCE.reset();
  }

  /**
   * The whole claim: broken, then served — and served with <b>upstream's</b> bytes rather than
   * whatever the broken row referred to.
   *
   * <p>The byte comparison is the half that makes this a repair instead of a trick. A heal that
   * answered from anywhere but a fresh fetch would be a cache inventing content, which is worse than
   * the 500 it replaced.
   */
  @Test
  void aCachedEntryThatCannotBeServedIsEvictedAndFetchedAgainInTheSameRequest() {
    String artifactId = "broken-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    byte[] jar = TinyArtifact.jar(artifactId);
    StubMavenRepository.INSTANCE.hostFile(path, jar);

    try (MavenClient maven = client()) {
      assertArrayEquals(jar, maven.get(PROXY, path).body());
      assertEquals(1, StubMavenRepository.INSTANCE.fileRequests());

      breakTheCachedRow(PROXY, path);

      HttpResponse<byte[]> healed = maven.get(PROXY, path);
      assertEquals(200, healed.statusCode(), "a broken cache entry must not be a refusal");
      assertArrayEquals(jar, healed.body(), "and what comes back must be upstream's bytes");
      assertEquals(
          2,
          StubMavenRepository.INSTANCE.fileRequests(),
          "the heal is a real fetch, not a second read of the same row");

      // And the store is repaired rather than merely bypassed: the read after the heal is an
      // ordinary hit. A heal that served without rewriting the row would look identical to this
      // test up to here and would pay upstream on every request forever.
      assertArrayEquals(jar, maven.get(PROXY, path).body());
      assertEquals(2, StubMavenRepository.INSTANCE.fileRequests());
    }
  }

  /** HEAD takes the same path and must heal identically — it is what a resolver probes with first. */
  @Test
  void aHeadOfABrokenEntryHealsItToo() {
    String artifactId = "probed-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    StubMavenRepository.INSTANCE.hostFile(path, TinyArtifact.jar(artifactId));

    try (MavenClient maven = client()) {
      assertEquals(200, maven.head(PROXY, path).statusCode());
      breakTheCachedRow(PROXY, path);
      assertEquals(200, maven.head(PROXY, path).statusCode());
    }
  }

  /**
   * THE SAFETY PROPERTY, and the one worth the most: a hosted repository never heals, because
   * healing there would mean deleting a jar this platform published on the strength of one
   * unreadable read.
   *
   * <p>{@code maven_artifact} is one table for both maven types and a path is all that separates a
   * cached row from a deployed one, so the decision is the repository's TYPE and nothing else. A
   * hosted row whose bytes are gone stays exactly where it is and answers 404 — there is no upstream
   * to ask, so there is nothing an eviction could achieve except loss.
   */
  @Test
  void aHostedRepositoryNeverEvictsItsOwnRowNoMatterWhatTheStoreSays() {
    String artifactId = "published-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";

    try (MavenClient maven = client()) {
      assertEquals(201, maven.put(HOSTED, path, TinyArtifact.jar(artifactId)).statusCode());
      breakTheCachedRow(HOSTED, path);

      assertEquals(404, maven.get(HOSTED, path).statusCode());
      assertTrue(
          artifacts.findOne(HOSTED, path).isPresent(),
          "a published row must survive a read it could not serve");
    }
  }

  /**
   * An upstream {@code 404} is upstream's answer and not a fault in the store, so nothing is
   * evicted and nothing loops.
   *
   * <p>This is the failure a self-healing cache invites and the reason the heal is triggered by the
   * SERVE and never by the STATUS. Were it the status, a path upstream genuinely does not have would
   * be evicted and refetched on every single request — the cache turned into an amplifier, with
   * nothing to heal at the end of it. Here the cached row is intact, so it serves, and the absent
   * path is simply absent both times it is asked for.
   */
  @Test
  void anUpstreamRefusalEvictsNothingAndIsNotALoop() {
    String cached = "kept-" + RUN + "-" + UNIQUE.incrementAndGet();
    String cachedPath = GROUP_PATH + "/" + cached + "/1.0.0/" + cached + "-1.0.0.jar";
    byte[] jar = TinyArtifact.jar(cached);
    StubMavenRepository.INSTANCE.hostFile(cachedPath, jar);

    String absent = "absent-" + RUN + "-" + UNIQUE.incrementAndGet();
    String absentPath = GROUP_PATH + "/" + absent + "/1.0.0/" + absent + "-1.0.0.jar";

    try (MavenClient maven = client()) {
      assertArrayEquals(jar, maven.get(PROXY, cachedPath).body());
      int afterWarming = StubMavenRepository.INSTANCE.fileRequests();

      assertEquals(404, maven.get(PROXY, absentPath).statusCode());
      assertEquals(404, maven.get(PROXY, absentPath).statusCode());
      assertEquals(
          afterWarming + 2,
          StubMavenRepository.INSTANCE.fileRequests(),
          "a no is remembered nowhere — two asks, two fetches, and no eviction in between");

      // The neighbour is untouched: an upstream refusal for one path must not disturb the cache.
      assertArrayEquals(jar, maven.get(PROXY, cachedPath).body());
      assertEquals(afterWarming + 2, StubMavenRepository.INSTANCE.fileRequests());
      assertTrue(artifacts.findOne(PROXY, cachedPath).isPresent());
    }
  }

  /**
   * A broken entry whose upstream has since gone is a bounded loss and not a hidden one: the row is
   * dropped, the fetch fails, and the client is told what upstream said rather than what the row
   * did.
   */
  @Test
  void aBrokenEntryThatUpstreamNoLongerHasEndsAsUpstreamsAnswer() {
    String artifactId = "withdrawn-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    StubMavenRepository.INSTANCE.hostFile(path, TinyArtifact.jar(artifactId));

    try (MavenClient maven = client()) {
      assertEquals(200, maven.get(PROXY, path).statusCode());
      breakTheCachedRow(PROXY, path);
      StubMavenRepository.INSTANCE.reset();

      HttpResponse<String> gone = maven.getText(PROXY, path);
      assertEquals(404, gone.statusCode(), gone.body());
      assertTrue(
          gone.body().contains("upstream"),
          "the client is owed upstream's story, not the cache's: " + gone.body());
      assertTrue(
          artifacts.findOne(PROXY, path).isEmpty(),
          "an entry that could not be served and could not be refetched does not stay behind");
    }
  }

  /**
   * The other half of the same release: two builds resolving one new coordinate together is the
   * normal case, and both used to read "absent" and then insert.
   *
   * <p>Driven through the service rather than through the wire, because what is under test is what
   * happens when the row is ALREADY THERE — a test that had to win a race to observe it would be
   * testing the scheduler. The second call is made against a row the first one wrote and must be a
   * quiet no-op rather than the {@code duplicate key} that used to escape as a 500; the assertions
   * on the stored values are what say the first write is the one that stands.
   */
  @Test
  void recordingAPulledThroughPathTwiceIsAQuietNoOpAndNeverAConstraintViolation() {
    String artifactId = "raced-" + RUN + "-" + UNIQUE.incrementAndGet();
    String path = GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";

    registry.recordProxiedArtifact(PROXY, path, ABSENT_BLOB, 7);
    registry.recordProxiedArtifact(PROXY, path, "ab".repeat(32), 9);

    assertEquals(ABSENT_BLOB, artifacts.findOne(PROXY, path).orElseThrow().blobId);
    assertEquals(7, artifacts.findOne(PROXY, path).orElseThrow().sizeBytes);
  }

  /**
   * Points a cached row at a blob that is not stored — the row is present, its bytes cannot be
   * produced. A bulk update rather than a load-and-save so the test does not depend on the entity
   * being loadable, which is exactly what the real fault made untrue.
   */
  private void breakTheCachedRow(String repository, String path) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                assertEquals(
                    1,
                    artifacts.update(
                        "blobId = ?1 where repository = ?2 and path = ?3",
                        ABSENT_BLOB,
                        repository,
                        path),
                    "the fixture must actually have broken a row"));
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
