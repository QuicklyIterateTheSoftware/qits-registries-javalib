package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.dto.MirrorUpstreamSummary;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.OciMirrorUpstream;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorUpstreamRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The registered upstreams, and the one namespace each of them is reachable under.
 *
 * <p>Every write here creates or reconciles <b>two</b> rows in one transaction: the {@code
 * oci_mirror_upstream} row, and the {@code artifact_repository} row of type {@link
 * OciMirrorProfile#KEY} named by its slug. That pairing is what makes resolving {@code
 * /v2/quay/quarkus/…} a table read rather than a config lookup, and it is why the slug carries a
 * foreign key into the repository table.
 *
 * <p><b>Delete removes the upstream row and nothing else.</b> The repository row and every cached
 * manifest, tag and blob under it stay exactly where they are — the append-only posture (⚖2), and
 * the only shape consistent with a store that has never deleted a byte. What changes is the future:
 * a miss in that namespace can no longer be fetched, because nothing names the registry to fetch it
 * from. What is already cached keeps serving.
 *
 * <p>The three prefilled upstreams are re-ensured at boot beside the seeder's own rows, so a
 * deployment whose database predates this table gets them from the migration and one whose rows were
 * removed by hand does not silently stay without them. Both paths run through {@link #ensure}.
 */
@ApplicationScoped
public class OciMirrorUpstreams {

  /**
   * Docker Hub's domain, and the one upstream with rules of its own.
   *
   * <p>Two of them, both the docker daemon's own behaviour rather than anything invented here: a
   * single-component image under this namespace normalises to {@code library/<name>} ({@link
   * #normalize}), and a first path segment that names no repository at all remaps into this
   * namespace so a daemon-configured {@code registry-mirrors} client — which asks for bare Hub names
   * — is served (the footnote in §2.1). The endpoint the miss path will dial is {@code
   * registry-1.docker.io}, which is BX's problem and not modelled here.
   */
  public static final String DOCKER_HUB = "docker.io";

  /** Hub's implicit namespace for single-component images: {@code alpine} is {@code library/alpine}. */
  static final String HUB_LIBRARY = "library";

  /**
   * What a fresh deployment mirrors, and what the prefill in {@code V7__oci_mirror.sql} writes.
   *
   * <p>Three public registries with static domains — which is what makes them lineage material
   * rather than deployment data. Two of them are where every base image this platform builds on
   * actually lives; Hub is here because it costs nothing and because the optional Docker Desktop
   * {@code registry-mirrors} setting needs it to exist.
   */
  static final Map<String, String> DEFAULTS =
      new LinkedHashMap<>(
          Map.of(
              DOCKER_HUB, "hub",
              "quay.io", "quay",
              "registry.access.redhat.com", "redhat"));

  /**
   * A registry domain: dot-separated lowercase labels, optionally with a port.
   *
   * <p>Deliberately narrow. The domain is dialled by the miss path, so a value that is not a host is
   * a fetch that fails at request time in a service nobody is watching; refusing it at the API is
   * the cheaper place to find out.
   */
  private static final Pattern DOMAIN =
      Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:\\d{1,5})?");

  @Inject OciMirrorUpstreamRepository upstreams;
  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciManifestRepository manifests;

  /** Ensures the three prefilled upstreams exist. Idempotent — every boot runs it. */
  @ActivateRequestContext
  public void ensureDefaults() {
    DEFAULTS.forEach(this::ensure);
  }

  /**
   * Registers an upstream under a namespace, or returns the one already registered.
   *
   * <p>Idempotent on the pair, and a <b>slug change is refused</b> for the same reason a repository's
   * type is immutable: content is already cached under the old namespace, and moving the name would
   * strand it under a row nothing resolves to. Re-pointing an upstream is a delete and a create, so
   * the operator sees what happens to the cache.
   */
  @Transactional
  public OciMirrorUpstream ensure(String domain, String slug) {
    String cleanDomain = requireDomain(domain);
    String cleanSlug = requireSlug(slug);

    OciMirrorUpstream existing = upstreams.findByDomain(cleanDomain).orElse(null);
    if (existing != null) {
      if (!existing.slug.equals(cleanSlug)) {
        throw new BadRequestException(
            "upstream '"
                + cleanDomain
                + "' is already mirrored at '"
                + existing.slug
                + "'; a namespace is immutable because content is cached under it — delete and"
                + " re-create to move it");
      }
      return existing;
    }

    upstreams
        .findBySlug(cleanSlug)
        .ifPresent(
            clash -> {
              throw new BadRequestException(
                  "the namespace '" + cleanSlug + "' already mirrors " + clash.domain);
            });

    // The paired repository row, in this same transaction. A namespace that resolves to no row is a
    // 404 on every pull, so the two rows are never written apart.
    ArtifactRepository repository = repositories.findById(cleanSlug);
    if (repository == null) {
      repository = new ArtifactRepository();
      repository.name = cleanSlug;
      repository.type = OciMirrorProfile.KEY;
      repository.createdAt = Instant.now();
      repositories.persist(repository);
    } else if (!OciMirrorProfile.KEY.equals(repository.type)) {
      throw new BadRequestException(
          "'"
              + cleanSlug
              + "' is already a "
              + RepositoryTypeProfile.wireNameOf(repository.type)
              + " repository; a mirror namespace cannot share a name with one");
    }

    OciMirrorUpstream upstream = new OciMirrorUpstream();
    upstream.domain = cleanDomain;
    upstream.slug = cleanSlug;
    upstream.createdAt = Instant.now();
    upstreams.persist(upstream);
    return upstream;
  }

  /**
   * Removes an upstream. The paired repository row and everything cached under it stay — see the
   * class javadoc; this is the append-only posture, not an oversight.
   */
  @Transactional
  public void delete(String domain) {
    OciMirrorUpstream upstream = require(domain);
    upstreams.delete(upstream);
  }

  @ActivateRequestContext
  public List<MirrorUpstreamSummary> list() {
    List<MirrorUpstreamSummary> summaries = new ArrayList<>();
    for (OciMirrorUpstream upstream : upstreams.listBySlug()) {
      summaries.add(summarize(upstream));
    }
    return summaries;
  }

  @ActivateRequestContext
  public MirrorUpstreamSummary get(String domain) {
    return summarize(require(domain));
  }

  /** The upstream a namespace fronts, if one is still registered for it. */
  public Optional<OciMirrorUpstream> bySlug(String slug) {
    return upstreams.findBySlug(slug);
  }

  /** Docker Hub's namespace, if it is registered — the one upstream the remap footnote needs. */
  public Optional<OciMirrorUpstream> hub() {
    return upstreams.findByDomain(DOCKER_HUB);
  }

  /**
   * The image name as the upstream spells it.
   *
   * <p>One rule, and it is Hub's: {@code hub/alpine} means {@code library/alpine}, exactly as the
   * docker daemon expands a bare name. Keyed on the <b>domain</b> rather than on the slug, because
   * the slug is whatever an operator chose and the normalisation is a property of the registry.
   */
  static String normalize(OciMirrorUpstream upstream, String image) {
    if (upstream == null || !DOCKER_HUB.equals(upstream.domain) || image.indexOf('/') >= 0) {
      return image;
    }
    return HUB_LIBRARY + "/" + image;
  }

  private MirrorUpstreamSummary summarize(OciMirrorUpstream upstream) {
    return new MirrorUpstreamSummary(
        upstream.domain,
        upstream.slug,
        upstream.createdAt,
        manifests.countImages(upstream.slug));
  }

  private OciMirrorUpstream require(String domain) {
    return upstreams
        .findByDomain(domain == null ? null : domain.trim().toLowerCase(java.util.Locale.ROOT))
        .orElseThrow(() -> new NotFoundException("No such mirror upstream: " + domain));
  }

  private static String requireDomain(String domain) {
    String clean = domain == null ? "" : domain.trim().toLowerCase(java.util.Locale.ROOT);
    if (!DOMAIN.matcher(clean).matches()) {
      throw new BadRequestException(
          "'" + domain + "' is not a registry domain, e.g. quay.io");
    }
    return clean;
  }

  private static String requireSlug(String slug) {
    String clean = slug == null ? "" : slug.trim();
    if (!OciImageName.isComponent(clean)) {
      throw new BadRequestException(
          "'"
              + slug
              + "' is not a usable namespace; it must be one lowercase OCI name component, because"
              + " it is the first path segment of every pull through this mirror");
    }
    return clean;
  }
}
