package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Container images, served over the OCI Distribution API at {@code /v2} — the first protocol
 * profile, and the pattern every later one follows.
 *
 * <p>Its bytes do not go through {@link BlobService}: they arrive on the registry routes ({@code
 * eu.wohlben.qits.registry}), which talk to {@link BlobStore} directly — no media-type sniffing (a
 * gzipped tar layer sniffs to nothing and would 400), no required metadata keys, no {@code
 * artifact_record} row. A layer is addressed by its digest and nothing else.
 *
 * <p>So the validating upload path is refused and the cap is <b>zero</b> — "not applicable", not
 * "unlimited". A stray {@code POST /artifacts/api/repositories/<an oci repo>/blobs} fails {@code
 * accepts()} and is rejected before {@code BlobService} ever reads {@code maxBytes()}, and zero
 * rather than a plausible-looking number is deliberate: if this profile ever gains a media type, a
 * zero cap fails loudly at the first byte instead of quietly accepting a gigabyte down a path that
 * was never meant to carry one.
 *
 * <p>The real layer cap is {@code qits.artifacts.oci.max-layer-size} (default 1G), resolved in the
 * registry. It is a config knob and not a constant here because it has to move with {@code
 * quarkus.http.limits.max-body-size} — a deployment's disk budget, not a property of the format.
 */
@ApplicationScoped
public class OciImagesProfile implements RepositoryTypeProfile {

  public static final String KEY = "OCI_IMAGES";

  @Override
  public String key() {
    return KEY;
  }
}
