package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciMirrorTagCheck;
import eu.wohlben.qits.artifacts.entity.OciMirrorUpstream;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorTagCheckRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, and that is load-bearing rather than cargo cult: the
 * callers are raw Vert.x route handlers, which run with <b>no CDI request context and no
 * transaction</b>. {@code GitHostRoutes} is no precedent for this — it touches no database at all.
 * The precedents are {@code ArtifactsRepositorySeeder}, which carries {@code @ActivateRequestContext}
 * for exactly this reason, and {@code BlobService.upload}'s explicit {@code
 * QuarkusTransaction.requiringNew()}. Drop an annotation here and the manifest routes fail with
 * {@code ContextNotActiveException} at runtime only.
 *
 * <p>Blob streaming stays deliberately outside all of it. The bytes never enter a transaction, so a
 * slow gigabyte upload cannot time one out — and OCI blobs get no row at all, so there is nothing
 * for one to protect.
 */
@ApplicationScoped
public class OciRegistryService {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;
  @Inject OciMirrorTagCheckRepository tagChecks;
  @Inject BlobStore blobStore;
  @Inject OciMirrorUpstreams mirrors;
  @Inject OciAccessTracker accessTracker;

  /** A manifest resolved for serving: what to read, how big it is, and what to call it. */
  public record StoredManifest(String digest, String mediaType, long size) {}

  /**
   * A {@code <name>} resolved for reading: which rows to look in, and whether they are a mirror's.
   *
   * @param name the name to query rows by — <b>not</b> always the one the client sent, because a Hub
   *     namespace expands a single-component image and an unknown first segment may have been
   *     remapped into the Hub namespace
   * @param type the resolved repository's type, so a caller can say why a miss is a miss
   * @param upstreamDomain the registry the namespace fronts, or null for a hosted repository (and
   *     for a mirror whose upstream row was deleted while its cache stayed)
   */
  public record PullTarget(OciImageName name, String type, String upstreamDomain) {

    public boolean mirror() {
      return OciMirrorProfile.KEY.equals(type);
    }
  }

  /**
   * Resolves an OCI {@code <name>} for a <b>write</b>: the repository must exist and must be one
   * this registry accepts content into.
   *
   * <p>Repositories are not created implicitly. A push to an unknown first segment is {@code
   * NAME_UNKNOWN}, and an operator creates it with the ordinary {@code PUT
   * /artifacts/api/repositories/<name>} carrying {@code {"type":"oci-images"}} — the same endpoint,
   * the same token guard and the same immutable-type rule as every other repository. A typo
   * therefore fails loudly instead of quietly minting a namespace.
   *
   * <p>The one exception to "an operator creates it" is {@code qits}, the repository the platform's
   * own publish convention uses: {@link ArtifactsRepositorySeeder} seeds that row at startup, so a
   * fresh deployment accepts {@code qits/<application>:<sha>} with no manual step. Every other
   * namespace still has to be asked for.
   *
   * <p><b>A mirror namespace is refused by type</b>, with {@code 405} and the type's name in the
   * message — the npm two-part shape ({@code NpmRoutes.publish}). A pull-through cache that accepted
   * a push would let cached upstream content and pushed content share a namespace, which is the one
   * thing the separate type exists to prevent; and because it is the type refusing, no deployment
   * can configure its way past it and no repository can drift from one meaning to the other.
   *
   * <p>The row read is wrapped in {@link DbRetry#call} because it is the first database touch of
   * every push: without it a postgres cutover answers "no such image repository" for a repository
   * that exists, and a docker client acts on that. A plain read, so re-running it is free, and the
   * caller is a raw route handler that opens no transaction. Parsing the name is not database work
   * and stays outside.
   */
  @ActivateRequestContext
  public OciImageName requireOciRepository(String name) {
    OciImageName parsed = OciImageName.parse(name);
    return DbRetry.call(
        "oci repository lookup for " + parsed.repository(),
        () -> {
          ArtifactRepository repository = repositories.findById(parsed.repository());
          if (repository != null && OciMirrorProfile.KEY.equals(repository.type)) {
            throw new OciException(
                OciCode.UNSUPPORTED,
                405,
                "'"
                    + parsed.repository()
                    + "' is a pull-through cache of an upstream registry and accepts no pushes; push"
                    + " to an oci-images repository instead",
                Map.of(
                    "name",
                    parsed.full(),
                    "type",
                    RepositoryTypeProfile.wireNameOf(OciMirrorProfile.KEY)));
          }
          if (repository == null || !OciImagesProfile.KEY.equals(repository.type)) {
            throw new OciException(
                OciCode.NAME_UNKNOWN,
                "no such image repository; create it with PUT /artifacts/api/repositories/"
                    + parsed.repository()
                    + " {\"type\":\"oci-images\"}",
                Map.of("name", parsed.full()));
          }
          return parsed;
        });
  }

  /**
   * Resolves an OCI {@code <name>} for a <b>read</b>, through the upstream table.
   *
   * <p>Three answers, in this order, and the order is the whole of the precedence rule:
   *
   * <ol>
   *   <li>An {@code oci-images} row — the hosted registry, unchanged code.
   *   <li>An {@code oci-mirror} row — a registered namespace. The image name is normalised the way
   *       the upstream spells it, which today means Hub's {@code alpine} → {@code library/alpine}.
   *   <li>No row at all — the {@code registry-mirrors} remap. A daemon configured to mirror Docker
   *       Hub asks for bare Hub names ({@code /v2/library/alpine/…}), so a first segment naming no
   *       repository is answered out of the Hub namespace when one is registered. <b>Existing
   *       repositories always win</b>, so {@code /v2/qits/…} never reaches this; the known
   *       consequence is that a Hub organisation sharing a name with a local repository is shadowed,
   *       which is the correct precedence here.
   * </ol>
   *
   * <p>Nothing is fetched. A mirror namespace resolves whether or not anything is cached under it,
   * and a miss is the caller's 404 to phrase — see the routes, which say the mirror holds no copy
   * rather than that the image does not exist.
   *
   * <p>All three answers are rows, so the whole resolution is wrapped in {@link DbRetry#call} — the
   * repository read and the upstream read alike. It is the first database touch of every pull, and
   * a cutover that made it answer "no such image repository" would look to a docker client exactly
   * like an image that was never pushed. A plain read, and the caller is a raw route handler that
   * opens no transaction. Parsing the name is not database work and stays outside.
   */
  @ActivateRequestContext
  public PullTarget resolveForPull(String name) {
    OciImageName parsed = OciImageName.parse(name);
    return DbRetry.call(
        "oci pull resolution for " + parsed.full(),
        () -> {
          ArtifactRepository repository = repositories.findById(parsed.repository());

          if (repository != null && OciImagesProfile.KEY.equals(repository.type)) {
            return new PullTarget(parsed, OciImagesProfile.KEY, null);
          }
          if (repository != null && OciMirrorProfile.KEY.equals(repository.type)) {
            OciMirrorUpstream upstream = mirrors.bySlug(parsed.repository()).orElse(null);
            return mirrorTarget(
                parsed.repository(),
                OciMirrorUpstreams.normalize(upstream, parsed.image()),
                upstream);
          }
          if (repository == null) {
            OciMirrorUpstream hub = mirrors.hub().orElse(null);
            if (hub != null) {
              // The remap: the whole name the client sent becomes the image inside the Hub
              // namespace, so `library/alpine` is cached exactly where a `hub/library/alpine` pull
              // would put it.
              return mirrorTarget(hub.slug, parsed.full(), hub);
            }
          }
          throw new OciException(
              OciCode.NAME_UNKNOWN,
              "no such image repository; create it with PUT /artifacts/api/repositories/"
                  + parsed.repository()
                  + " {\"type\":\"oci-images\"}, or register a mirror upstream for it",
              Map.of("name", parsed.full()));
        });
  }

  private static PullTarget mirrorTarget(String slug, String image, OciMirrorUpstream upstream) {
    return new PullTarget(
        new OciImageName(slug, image, slug + "/" + image),
        OciMirrorProfile.KEY,
        upstream == null ? null : upstream.domain);
  }

  /**
   * Binds an already-promoted manifest to this name, and to a tag if the reference was one.
   *
   * @param digest the manifest's own digest, in bare hex, recomputed from the received bytes
   */
  @ActivateRequestContext
  @Transactional
  public void bindManifest(
      OciImageName name, String reference, String digest, String mediaType, long size) {
    OciManifest manifest =
        manifests.findOne(name.repository(), name.image(), digest).orElseGet(OciManifest::new);
    manifest.repository = name.repository();
    manifest.imageName = name.image();
    manifest.digest = digest;
    manifest.mediaType = mediaType;
    manifest.size = size;
    if (manifest.createdAt == null) {
      manifest.createdAt = Instant.now();
      manifests.persist(manifest);
    }

    if (!OciDigest.isDigest(reference)) {
      OciTag tag =
          tags.findOne(name.repository(), name.image(), reference).orElseGet(OciTag::new);
      boolean fresh = tag.tag == null;
      tag.repository = name.repository();
      tag.imageName = name.image();
      tag.tag = reference;
      tag.manifestDigest = digest;
      tag.updatedAt = Instant.now();
      if (fresh) {
        tags.persist(tag);
      }
    }
  }

  /**
   * Resolves a {@code <ref>} — a tag or a digest — to the manifest to serve.
   *
   * <p>The digest branch goes through {@code oci_manifest} rather than straight to the blob store,
   * which is the whole point of that table: the store dedupes globally, so a digest lookup that
   * skipped it would happily serve a manifest that was only ever pushed to some other repository.
   *
   * <p>Both reads are wrapped in {@link DbRetry#call} together, because an empty answer here is a
   * 404 the client believes: a cutover between the tag read and the manifest read would report a
   * manifest that is not there. Plain reads, and the caller is a raw route handler that opens no
   * transaction.
   */
  @ActivateRequestContext
  public Optional<StoredManifest> resolveManifest(OciImageName name, String reference) {
    return DbRetry.call(
        "oci manifest resolution for " + name.full() + ":" + reference,
        () -> {
          String tag = OciDigest.isDigest(reference) ? null : reference;
          String digest = tag == null ? OciDigest.hexOrNull(reference) : tags.findOne(name.repository(), name.image(), tag)
              .map(row -> row.manifestDigest).orElse(null);
          if (digest == null) {
            return Optional.empty();
          }
          return manifests.findOne(name.repository(), name.image(), digest)
              .map(manifest -> new StoredManifest(manifest.digest, manifest.mediaType, manifest.size));
        });
  }

  /** Records the manifest that the route actually selected after any mirror revalidation/fetch. */
  @ActivateRequestContext
  public void touchManifest(OciImageName name, String reference, String digest) {
    accessTracker.touchManifest(
        name.repository(),
        name.image(),
        digest,
        OciDigest.isDigest(reference) ? null : reference,
        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  /**
   * Checks every digest a manifest references, before anything is bound.
   *
   * <p>An index's children must be manifests already known to this {@code (repository, image)} — a
   * blob with the right bytes is not enough, because that is exactly the cross-repository leak the
   * manifest table exists to prevent. An image manifest's references are ordinary blobs.
   */
  @ActivateRequestContext
  public void requireReferencesExist(OciImageName name, boolean index, List<String> references) {
    for (String digest : references) {
      boolean present =
          index
              ? manifests.exists(name.repository(), name.image(), digest)
              : blobStore.exists(digest);
      if (!present) {
        throw new OciException(
            index ? OciCode.MANIFEST_UNKNOWN : OciCode.MANIFEST_BLOB_UNKNOWN,
            index
                ? "the index references a manifest this image does not have"
                : "the manifest references a blob that has not been uploaded",
            Map.of("digest", OciDigest.wire(digest)));
      }
    }
  }

  /** Tag names for {@code tags/list}, lexically ordered and paged by the {@code ?last=} cursor. */
  @ActivateRequestContext
  public List<String> listTags(OciImageName name, String after, int limit) {
    return tags.listTagNames(name.repository(), name.image(), after, limit);
  }

  // --- the mirror's tag freshness ---------------------------------------------------------------

  /**
   * When this mirrored tag was last checked against its upstream, or empty if it never was.
   *
   * <p>Only the mirror writes these rows, but nothing here enforces that: the miss path asks only
   * about tags in a namespace it already resolved as {@code OCI_MIRROR}, and a second type check
   * would be a second place for the two to disagree.
   */
  @ActivateRequestContext
  public Optional<Instant> mirrorTagCheckedAt(OciImageName name, String tag) {
    return tagChecks
        .findOne(name.repository(), name.image(), tag)
        .map(check -> check.checkedAt);
  }

  /**
   * Records that this tag has just been agreed with its upstream — by a {@code HEAD} that found the
   * digest unchanged, or by a fetch that stored new bytes.
   *
   * <p>Deliberately <b>not</b> called when the upstream could not be reached. A failed check that
   * moved this timestamp would suppress the next attempt for a whole TTL, which is the opposite of
   * what serving stale is for: stale bytes go out now, and the next request still tries.
   */
  @ActivateRequestContext
  @Transactional
  public void recordMirrorTagCheck(OciImageName name, String tag, Instant checkedAt) {
    OciMirrorTagCheck check =
        tagChecks
            .findOne(name.repository(), name.image(), tag)
            .orElseGet(OciMirrorTagCheck::new);
    boolean fresh = check.tag == null;
    check.repository = name.repository();
    check.imageName = name.image();
    check.tag = tag;
    check.checkedAt = checkedAt;
    if (fresh) {
      tagChecks.persist(check);
    }
  }

  /**
   * Deletes one tag row, and the mirror freshness row beside it — the only way a tag ever leaves
   * this registry.
   *
   * <p><b>Package-private, reached only through {@link OciRegistryCollection}</b> and called only
   * by the {@code gc} module's {@code OciImageGcStrategy} and {@code OciMirrorGcAdapter} — the same
   * shape and the same reason as {@code NpmRegistryService.collect} and {@code BlobStore.delete}:
   * the client-facing {@code 405} on {@code /v2} deletes stays exactly as it is, and no route
   * reaches this. Which tags die is the strategy's rule; this only knows how a row is removed.
   *
   * <p><b>Both OCI types come through here, and always did.</b> Nothing in this method reads a
   * repository's type — a tag row is a tag row — so the mirror's eviction needed no widening of the
   * door itself. What it needed is the line below: an {@code oci_mirror_tag_check} row keyed by the
   * same {@code (repository, image, tag)} outlives the tag it describes, and a freshness row for a
   * tag that no longer exists is a row nothing will ever read or delete. Doing it here rather than
   * in the caller is the funnel's whole point — the auxiliary row cannot be forgotten by a second
   * caller, because there is no second way in. A hosted repository never has one, so this is a
   * no-op for {@code oci-images}.
   *
   * <p>The manifest the tag named is not touched — whether it survives is a reachability question
   * the strategy answers, and its blobs are the sweep's question after that.
   *
   * @throws IllegalStateException no such tag row — the store moved since the plan was computed,
   *     and a plan that raced a re-push must surface rather than delete by coordinates alone
   */
  @ActivateRequestContext
  @Transactional
  void collectTag(String repository, String imageName, String tag) {
    OciTag row =
        tags.findOne(repository, imageName, tag)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no such tag "
                            + imageName
                            + ":"
                            + tag
                            + " to collect — the store moved since the plan was computed"));
    tags.delete(row);
    tagChecks.findOne(repository, imageName, tag).ifPresent(tagChecks::delete);
  }

  /**
   * Deletes one manifest row — the untagged-manifest half of OCI garbage collection.
   *
   * <p>Package-private, same rule as {@link #collectTag}, and called by both OCI types' collectors.
   * One guarantee is the mechanism's rather than the policy's: <b>a manifest a tag still names is
   * refused.</b> Neither collector condemns one — reachability from kept tags is docker's whole
   * rule, and the mirror only ever enumerates manifests no tag names — so this firing means the
   * plan was stale or wrong, and refusing beats serving a tag whose manifest row is gone.
   *
   * <p>The manifest's blobs — its own bytes included — are not touched. Blobs dedupe across every
   * repository type, so what may be unlinked is never one type's question; the sweep answers it.
   *
   * @throws IllegalStateException no such manifest row, or a tag still names it
   */
  @ActivateRequestContext
  @Transactional
  void collectManifest(String repository, String imageName, String digest) {
    OciManifest row =
        manifests
            .findOne(repository, imageName, digest)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no such manifest "
                            + imageName
                            + "@sha256:"
                            + digest
                            + " to collect — the store moved since the plan was computed"));
    for (OciTag tag : tags.listByImage(repository, imageName)) {
      if (tag.manifestDigest.equals(digest)) {
        throw new IllegalStateException(
            "refusing to collect "
                + imageName
                + "@sha256:"
                + digest
                + " — the "
                + tag.tag
                + " tag still names it, and a tag whose manifest row is gone is a broken"
                + " coordinate to every client");
      }
    }
    manifests.delete(row);
  }
}
