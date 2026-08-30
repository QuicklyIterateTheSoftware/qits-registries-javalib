package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.MirrorUpstreamSummary;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The upstream registry, and the two rows that have to move together.
 *
 * <p>Every case here is about the pairing. An upstream row without its {@code oci-mirror} repository
 * row is a namespace that resolves to nothing; a repository row without its upstream is a namespace
 * nothing can be fetched into. The API writes both or neither, and the one asymmetry — delete leaves
 * the repository behind — is a recorded decision (⚖2, append-only) rather than a leak.
 */
@QuarkusTest
class OciMirrorUpstreamsTest extends SeededStoreFixture {

  @Inject OciMirrorUpstreams upstreams;
  @Inject OciManifestFootprints footprints;

  @Test
  void registeringAnUpstreamCreatesTheNamespaceItResolvesTo() {
    upstreams.ensure("quay.io", "quay");

    assertEquals(
        OciMirrorProfile.KEY, repositoryService.require("quay").type,
        "the paired repository row, and it is a mirror — a hosted row would accept pushes");
    MirrorUpstreamSummary summary = upstreams.get("quay.io");
    assertEquals("quay", summary.slug());
    assertEquals(0, summary.cachedImages(), "a fresh mirror holds nothing, and says so");
  }

  @Test
  void registeringTheSamePairTwiceIsANoOp() {
    // Every boot re-ensures the three defaults, so "already there" is the normal case.
    upstreams.ensure("quay.io", "quay");
    upstreams.ensure("quay.io", "quay");

    assertEquals(List.of("quay"), upstreams.list().stream().map(MirrorUpstreamSummary::slug).toList());
  }

  @Test
  void movingARegisteredUpstreamToAnotherNamespaceIsRefused() {
    // Content is cached under the old namespace. Renaming it would strand every cached manifest
    // under a row nothing resolves to — so re-pointing an upstream is a delete and a create, where
    // an operator sees what happens to the cache.
    upstreams.ensure("quay.io", "quay");

    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> upstreams.ensure("quay.io", "redhat"));
    assertTrue(refused.getMessage().contains("immutable"), refused.getMessage());
  }

  @Test
  void twoUpstreamsCannotShareOneNamespace() {
    upstreams.ensure("quay.io", "quay");

    assertThrows(BadRequestException.class, () -> upstreams.ensure("mirror.gcr.io", "quay"));
  }

  @Test
  void aNamespaceCannotShadowARepositoryOfAnotherType() {
    // The registry resolves a namespace by its first path segment, and a name means one thing. A
    // mirror slug landing on the platform's own image repository would make `qits/…` ambiguous.
    repositoryService.ensure("qits", OciImagesProfile.KEY);

    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> upstreams.ensure("quay.io", "qits"));
    assertTrue(refused.getMessage().contains("oci-images"), refused.getMessage());
  }

  @Test
  void aSlugThatIsNotOneNameComponentIsRefusedAtTheApiRatherThanAtPullTime() {
    // It is the first path segment of every pull through the mirror. A slug with a slash or a
    // capital in it names a namespace no client can address, and finding that out on a failed
    // `docker pull` reads as a broken registry.
    assertThrows(BadRequestException.class, () -> upstreams.ensure("quay.io", "Quay"));
    assertThrows(BadRequestException.class, () -> upstreams.ensure("quay.io", "quay/io"));
    assertThrows(BadRequestException.class, () -> upstreams.ensure("not a domain", "quay"));
  }

  @Test
  void deletingAnUpstreamKeepsEveryByteItCached() throws Exception {
    // ⚖2, in the one place it is observable: this platform has never deleted a byte, and stopping a
    // mirror is not a deletion request. What ends is the future — nothing new can be fetched into
    // the namespace, because nothing names the registry to fetch it from.
    MirrorStore mirror = seedMirror();
    upstreams.ensure("quay.io", MIRROR_REPO);

    upstreams.delete("quay.io");

    assertThrows(NotFoundException.class, () -> upstreams.get("quay.io"));
    assertEquals(
        OciMirrorProfile.KEY,
        repositoryService.require(MIRROR_REPO).type,
        "the namespace still resolves, so what is cached still serves");
    // Reachability read straight off the manifest closure. The store-wide census this used to ask
    // stayed with the service (it counts docs and daemon rows too), but the question is the same
    // one and the footprints are where its OCI answer came from.
    assertTrue(
        footprints
            .union(ociManifests.listByImage(MIRROR_REPO, MIRROR_IMAGE))
            .containsKey(mirror.layer()),
        "and the bytes are still live, not orphaned by the deletion");
  }

  @Test
  void theListCountsWhatEachNamespaceHasActuallyCached() throws Exception {
    // The one number the management panel (workstream CA) shows beside a domain, and the only
    // honest measure of a cache: not what it could hold, what somebody pulled.
    seedMirror();
    upstreams.ensure("quay.io", MIRROR_REPO);
    upstreams.ensure("docker.io", "hub");

    assertEquals(
        List.of("hub", "quay"), upstreams.list().stream().map(MirrorUpstreamSummary::slug).toList());
    assertEquals(1, upstreams.get("quay.io").cachedImages());
    assertEquals(0, upstreams.get("docker.io").cachedImages());
  }
}
