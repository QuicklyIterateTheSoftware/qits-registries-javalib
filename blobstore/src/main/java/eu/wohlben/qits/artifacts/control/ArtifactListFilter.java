package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.BadRequestException;
import java.time.Instant;

/** Inclusive repository-listing bounds shared by CI records and OCI tags. */
public record ArtifactListFilter(
    Instant accessedAfter,
    Instant accessedBefore,
    Instant createdAfter,
    Instant createdBefore,
    Long minSize,
    Long maxSize,
    Boolean neverAccessed) {

  public static final ArtifactListFilter NONE =
      new ArtifactListFilter(null, null, null, null, null, null, null);

  public ArtifactListFilter {
    if (minSize != null && minSize < 0 || maxSize != null && maxSize < 0) {
      throw new BadRequestException("size bounds must be non-negative");
    }
    if (minSize != null && maxSize != null && minSize > maxSize) {
      throw new BadRequestException("min-size must not exceed max-size");
    }
    if (accessedAfter != null && accessedBefore != null && accessedAfter.isAfter(accessedBefore)) {
      throw new BadRequestException("accessed-after must not exceed accessed-before");
    }
    if (createdAfter != null && createdBefore != null && createdAfter.isAfter(createdBefore)) {
      throw new BadRequestException("created-after must not exceed created-before");
    }
    if (Boolean.TRUE.equals(neverAccessed) && (accessedAfter != null || accessedBefore != null)) {
      throw new BadRequestException("never-accessed=true cannot be combined with access bounds");
    }
  }

  public boolean matches(long size, Instant createdAt, Instant accessedAt) {
    return (minSize == null || size >= minSize)
        && (maxSize == null || size <= maxSize)
        && (createdAfter == null || !createdAt.isBefore(createdAfter))
        && (createdBefore == null || !createdAt.isAfter(createdBefore))
        && (neverAccessed == null
            || neverAccessed == (accessedAt == null))
        && (accessedAfter == null || accessedAt != null && !accessedAt.isBefore(accessedAfter))
        && (accessedBefore == null || accessedAt != null && !accessedAt.isAfter(accessedBefore));
  }
}
