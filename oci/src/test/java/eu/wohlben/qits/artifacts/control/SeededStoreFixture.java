package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.BlobStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A cached mirror image, small enough to reason about.
 *
 * <p>The cross-type half of qits-platform-artifacts' fixture — one blob an image layer and an npm
 * tarball both name — did not travel: it asked whether a blob survives when one type lets go, which
 * is a question about the census and the collector, and both stayed with the service. What is left
 * is what the OCI cases here actually need.
 */
abstract class SeededStoreFixture extends ArtifactsTestSupport {

  @Inject ArtifactRepositoryService repositoryService;
  @Inject BlobStore blobStore;

  static final String MIRROR_REPO = "quay";
  static final String MIRROR_IMAGE = "quarkus/ubi9-quarkus-mandrel-builder-image";

  static final int MIRROR_CONFIG = 11;
  static final int MIRROR_LAYER = 700;
  static final int ABSENT_CHILD = 900;

  /** What {@link #seedMirror()} built. */
  record MirrorStore(String config, String layer, String child, String index, String absentChild) {}

  /**
   * One cached multi-arch image in a mirror namespace, <b>with one child that was never fetched</b>.
   *
   * <p>That missing child is the fixture's whole point. A push arrives children-first — the registry
   * refuses an index whose children it does not have — but a <em>pull</em> arrives index-first, and
   * the mirror binds it immediately and fetches children lazily, each on its own miss, so it never
   * pays an upstream for an architecture nobody pulled. A mirror index referencing a child with no
   * local row is therefore the normal state of a partially-pulled image, not a corruption, and every
   * reader that walks manifests has to survive it.
   */
  MirrorStore seedMirror() throws IOException {
    repositoryService.ensure(MIRROR_REPO, OciMirrorProfile.KEY);

    String config = store(filled(MIRROR_CONFIG, (byte) 6));
    String layer = store(filled(MIRROR_LAYER, (byte) 7));
    byte[] childBytes = imageManifest(config, Map.of(layer, (long) MIRROR_LAYER), MIRROR_CONFIG);
    String child = store(childBytes);
    // Never stored and never rowed: the architecture nobody pulled.
    String absentChild = "a".repeat(64);
    byte[] indexBytes =
        indexManifest(Map.of(child, (long) childBytes.length, absentChild, (long) ABSENT_CHILD));
    String index = store(indexBytes);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(
                  mirrorManifest(child, childBytes.length, OciMediaTypes.OCI_MANIFEST_V1));
              ociManifests.persist(
                  mirrorManifest(index, indexBytes.length, OciMediaTypes.OCI_INDEX_V1));
              ociTags.persist(mirrorTag("jdk-25", index));
            });

    for (String blobId : List.of(config, layer, child, index)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new MirrorStore(config, layer, child, index, absentChild);
  }

  private static OciManifest mirrorManifest(String digest, long size, String mediaType) {
    OciManifest row = new OciManifest();
    row.repository = MIRROR_REPO;
    row.imageName = MIRROR_IMAGE;
    row.digest = digest;
    row.mediaType = mediaType;
    row.size = size;
    row.createdAt = Instant.now();
    return row;
  }

  private static OciTag mirrorTag(String name, String digest) {
    OciTag row = new OciTag();
    row.repository = MIRROR_REPO;
    row.imageName = MIRROR_IMAGE;
    row.tag = name;
    row.manifestDigest = digest;
    row.updatedAt = Instant.now();
    return row;
  }

  /** A real OCI index — the children are manifests, not blobs, which is what makes the walk recurse. */
  static byte[] indexManifest(Map<String, Long> children) {
    List<String> descriptors = new ArrayList<>();
    children.forEach(
        (digest, size) ->
            descriptors.add(
                "{\"mediaType\":\""
                    + OciMediaTypes.OCI_MANIFEST_V1
                    + "\",\"digest\":\"sha256:"
                    + digest
                    + "\",\"size\":"
                    + size
                    + "}"));
    return ("{\"schemaVersion\":2,\"mediaType\":\""
            + OciMediaTypes.OCI_INDEX_V1
            + "\",\"manifests\":["
            + String.join(",", descriptors)
            + "]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  String store(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
    blobStore.promote(staged);
    return staged.sha256();
  }

  static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }

  /** A real OCI image manifest — the footprint parser reads these bytes, so a stub proves nothing. */
  static byte[] imageManifest(String configDigest, Map<String, Long> layers, int configSize) {
    List<String> descriptors = new ArrayList<>();
    layers.forEach(
        (digest, size) ->
            descriptors.add(
                "{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"digest\":\"sha256:"
                    + digest
                    + "\",\"size\":"
                    + size
                    + "}"));
    return ("{\"schemaVersion\":2,\"mediaType\":\""
            + OciMediaTypes.OCI_MANIFEST_V1
            + "\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
            + "\"digest\":\"sha256:"
            + configDigest
            + "\",\"size\":"
            + configSize
            + "},\"layers\":["
            + String.join(",", descriptors)
            + "]}")
        .getBytes(StandardCharsets.UTF_8);
  }
}
