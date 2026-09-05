package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A version identity that outlives its {@link NpmVersion} row — what garbage collection leaves
 * behind when it removes a version.
 *
 * <p><b>Why the identity has to survive the row.</b> Version immutability is enforced by looking
 * the row up: publishing over a version that exists is {@code 403}, publishing a version that does
 * not exist is a fresh publish. Deleting a row therefore re-opens its name, and the same coordinate
 * could then resolve to different bytes than it once did — the one mutability this registry exists
 * to refuse. So a collected version keeps a stub of itself here, and {@code
 * NpmRegistryService.publish} consults it.
 *
 * <p><b>This is an identity, not an archive.</b> No manifest, no integrity, no shasum: nothing may
 * ever be served from a tombstone, and holding the fields that would make that possible is how it
 * would eventually happen. {@link #tarballBlobId} stays a bare content address for the same reason —
 * it names bytes, it does not hold them, and no packument can be assembled from it.
 *
 * <p><b>{@link #tarballBlobId} stopped being provenance on 2026-09-05 and became evidence.</b> It is
 * what lets {@code NpmRegistryService.publish} tell a RESTORE from a reuse: a republish carrying the
 * same blob id is the same bytes under the same name, which is the one thing this table exists to
 * guarantee rather than a breach of it. It stays <b>nullable</b>, and a null still refuses every
 * republish — "I cannot tell which bytes these were" is not evidence that these are them.
 *
 * <p><b>npm's alone.</b> Docker needs no equivalent: an {@link OciTag} is a movable pointer by
 * design and re-pushing one has always been legal, so a deletion there weakens no promise. The
 * resemblance between the two GC strategies stops well before this table.
 */
@Entity
@Table(name = "npm_version_tombstone")
@IdClass(NpmVersionTombstoneId.class)
public class NpmVersionTombstone extends PanacheEntityBase {

  @Id public String repository;

  @Id
  @Column(name = "package_name")
  public String packageName;

  @Id
  @Column(length = 128)
  public String version;

  /** The blob the deleted version's tarball was, for tracing a reclaim back to its identity. */
  @Column(name = "tarball_blob_id", length = 64)
  public String tarballBlobId;

  @Column(name = "collected_at", nullable = false)
  public Instant collectedAt;
}
