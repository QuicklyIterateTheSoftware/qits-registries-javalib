package eu.wohlben.qits.artifacts.entity;

import java.util.Locale;
import java.util.Set;

/**
 * A repository <b>type</b> = its validation/convention profile over the shared blob core: which
 * media types it accepts, which metadata keys it requires, its per-upload size cap, and whether the
 * validating upload path may write to it at all.
 *
 * <p><b>Registration is open.</b> This was a closed enum, so the core had to name every package
 * format that existed. It does not any more: a profile is an {@code @ApplicationScoped} CDI bean,
 * and {@code RepositoryTypeProfiles} resolves a stored key to whichever bean claims it. The core
 * ships the two CI profiles; npm, maven and OCI ship theirs from qits-registries; a service that
 * owns a format of its own ships that one. Nothing in the core enumerates them.
 *
 * <p>Two flavours, and the difference is {@link #allowsValidatedUploads()}:
 *
 * <ul>
 *   <li>A <b>validated</b> type — the two CI ones — takes its bytes through {@code
 *       BlobService.upload}, which sniffs the media type, enforces the required keys and streams
 *       against the cap.
 *   <li>A <b>protocol</b> type takes its bytes through its own wire routes straight to {@code
 *       BlobStore}: no sniffing (a gzipped tar layer sniffs to nothing and would 400), no required
 *       keys, and a cap that is a config knob in the format's own module because it has to move
 *       with {@code quarkus.http.limits.max-body-size}. Such a profile refuses {@code
 *       BlobService.upload} outright, so a stray {@code POST
 *       /artifacts/api/repositories/<one of them>/blobs} is rejected before a single byte is
 *       staged, and its {@link #maxBytes()} of zero means "not applicable" rather than
 *       "unlimited" — if it ever gains a media type, a zero cap fails loudly at the first byte
 *       instead of quietly accepting a gigabyte down a path never meant to carry one.
 * </ul>
 *
 * <p>{@link #key()} is the <b>stored</b> form, written verbatim into {@code
 * artifact_repository.type} — the screaming-snake spelling the closed enum's constants had, so
 * existing rows and the services' {@code ck_artifact_repository_type} check constraint keep their
 * meaning. Contributing a profile whose key is not in that constraint is still a schema change as
 * well as a code change.
 */
public interface RepositoryTypeProfile {

  /**
   * The stored key, e.g. {@code CI_SCREENSHOTS}. Immutable once a row carries it, and unique across
   * all contributed profiles.
   */
  String key();

  /**
   * Whether {@code BlobService}'s validating upload path may write here. False for protocol types
   * (and every pull-through cache), which carry their bytes on their own wire routes.
   */
  default boolean allowsValidatedUploads() {
    return false;
  }

  /** The media types the validating upload path accepts. Empty on a protocol type. */
  default Set<String> allowedMediaTypes() {
    return Set.of();
  }

  /** The metadata keys an upload must carry. Empty on a protocol type. */
  default Set<String> requiredMetadataKeys() {
    return Set.of();
  }

  /** The per-upload cap the store enforces while streaming. Zero on a protocol type. */
  default long maxBytes() {
    return 0L;
  }

  /** Whether an upload of this media type may proceed. Always false on a protocol type. */
  default boolean accepts(String mediatype) {
    return allowsValidatedUploads() && allowedMediaTypes().contains(mediatype);
  }

  /** The wire form (kebab-case, e.g. {@code ci-screenshots}) — the stored key isn't the API. */
  default String wireName() {
    return wireNameOf(key());
  }

  /** The kebab wire form of a stored key. */
  static String wireNameOf(String key) {
    return key == null ? null : key.toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** The stored key of a kebab wire form; also tolerant of the stored form itself. */
  static String keyOfWireName(String wireName) {
    return wireName == null ? null : wireName.trim().toUpperCase(Locale.ROOT).replace('-', '_');
  }
}
