package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.blobstore.control.BlobStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a manifest costs: every blob it reaches, with each blob's size.
 *
 * <p>The store has no size table. OCI layers and configs get no database row at all — only manifests
 * do, and {@code oci_manifest.size_bytes} is the size of the manifest <em>JSON</em>, about a
 * kilobyte, which is three thousandths of what the store actually holds. The byte counts live in two
 * places: the stored blob itself, and the {@code size} fields inside the manifest document. This class
 * reads the second, because those are the numbers a digest covers.
 *
 * <p><b>A footprint is a set, not a sum.</b> Blobs are content-addressed and deduped globally, so
 * adding two manifests' sizes double-counts everything they share — which, for an image rebuilt
 * twenty times off the same base, is nearly all of it. Every figure the explorer shows is therefore
 * built by merging maps and summing once at the end: per tag (one manifest), per image (the union
 * over its manifests), per repository, and across the whole store.
 *
 * <p><b>Nothing here is ever invalidated, and that is a property of the key rather than an
 * oversight.</b> A manifest is content-addressed: the bytes behind {@code (repository, image,
 * digest)} cannot change, so a computed footprint stays correct forever. A push adds a key; it never
 * makes an existing one wrong. The <em>aggregates</em> do change, and they are deliberately not
 * cached — they are recomputed from the manifest rows on every request, which is a few thousand map
 * operations over an index scan and is cheaper than being wrong. The stored-byte figures are the one
 * thing this cannot answer, and they come from {@link BlobDiskIndex}.
 *
 * <p>The cold cost is bounded by the same fact: a first request parses every manifest in the
 * repository, which for this store is 155 documents totalling 160 kB.
 */
@ApplicationScoped
public class OciManifestFootprints {

  /**
   * The ceiling on a document this will read. It matches {@code
   * qits.artifacts.oci.max-manifest-size}'s default rather than reading the key, because this is a
   * defence and not a policy: an index whose child has no row could otherwise point this at a
   * gigabyte layer, and reading that into memory to discover it is not JSON is the one failure mode
   * worth ruling out by construction.
   */
  private static final long MAX_DOCUMENT_BYTES = 4L * 1024 * 1024;

  private final Map<Key, Map<String, Long>> cache = new ConcurrentHashMap<>();

  @Inject OciManifestRepository manifests;
  @Inject OciManifestParser parser;
  @Inject BlobStore blobStore;

  private record Key(String repository, String imageName, String digest) {}

  /**
   * Every blob this manifest reaches — itself, its config and layers, or, for an index, everything
   * its children reach.
   *
   * @return digest (bare hex) to bytes, unmodifiable
   */
  public Map<String, Long> of(OciManifest manifest) {
    Key key = new Key(manifest.repository, manifest.imageName, manifest.digest);
    Map<String, Long> hit = cache.get(key);
    if (hit != null) {
      return hit;
    }
    Map<String, Long> computed = new HashMap<>();
    accumulate(
        manifest.repository,
        manifest.imageName,
        manifest.digest,
        manifest.mediaType,
        manifest.size,
        computed,
        new HashSet<>());
    Map<String, Long> footprint = Map.copyOf(computed);
    // A plain put rather than computeIfAbsent: this recurses, and ConcurrentHashMap forbids a
    // recursive update of the map being computed. Two threads racing here compute the same answer
    // from the same immutable bytes, so the loser costs a parse and nothing else.
    cache.put(key, footprint);
    return footprint;
  }

  /** The union of several manifests' footprints — the shape every aggregate figure is built from. */
  public Map<String, Long> union(Iterable<OciManifest> subjects) {
    Map<String, Long> union = new HashMap<>();
    for (OciManifest subject : subjects) {
      of(subject).forEach(union::putIfAbsent);
    }
    return union;
  }

  /** Sums a footprint. Named, because summing the wrong thing is this feature's whole hazard. */
  public static long sum(Map<String, Long> footprint) {
    long total = 0;
    for (long size : footprint.values()) {
      total += size;
    }
    return total;
  }

  private void accumulate(
      String repository,
      String imageName,
      String digest,
      String mediaType,
      long size,
      Map<String, Long> into,
      Set<String> visited) {
    if (!visited.add(digest)) {
      return;
    }
    into.putIfAbsent(digest, size);

    byte[] document = readDocument(digest);
    if (document == null) {
      return;
    }
    OciManifestParser.SizedReferences references = parser.sizedReferences(document, mediaType);
    if (references == null) {
      return;
    }
    if (!references.index()) {
      references.references().forEach(into::putIfAbsent);
      return;
    }
    // An index's children are manifests, and their own layers are what cost anything. The row is
    // authoritative for a child's type and size; without one it can only be counted as it stands.
    references
        .references()
        .forEach(
            (childDigest, declaredSize) -> {
              OciManifest row = manifests.findOne(repository, imageName, childDigest).orElse(null);
              accumulate(
                  repository,
                  imageName,
                  childDigest,
                  row == null ? null : row.mediaType,
                  row == null ? declaredSize : row.size,
                  into,
                  visited);
            });
  }

  /** The manifest's bytes, or null if they are absent or too large to be a manifest. */
  private byte[] readDocument(String digest) {
    try {
      if (!blobStore.exists(digest) || blobStore.size(digest) > MAX_DOCUMENT_BYTES) {
        return null;
      }
      try (InputStream in = blobStore.open(digest)) {
        return in.readAllBytes();
      }
    } catch (IOException | RuntimeException unreadable) {
      // A store view must not fail because one document is missing; the manifest's own size is
      // already counted, and what it references is simply unknown.
      return null;
    }
  }
}
