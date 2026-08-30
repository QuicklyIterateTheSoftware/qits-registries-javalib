package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.time.Instant;

/** Panache DAO for {@link ArtifactRecord} (keyed by its String UUID row id). */
@ApplicationScoped
public class ArtifactRecordRepository implements PanacheRepositoryBase<ArtifactRecord, String> {

  /**
   * All records in a repository, newest-first by the server-stamped createdAt (indexed columns).
   */
  public List<ArtifactRecord> listByRepositoryNewestFirst(String repository) {
    return list("repository = ?1 order by createdAt desc, id desc", repository);
  }

  /**
   * Any record pointing at this content within the repository — used to resolve the stored
   * mediatype when serving (all records for one blobId agree, since the mediatype is sniffed from
   * the bytes).
   */
  public java.util.Optional<ArtifactRecord> findByRepositoryAndBlob(
      String repository, String blobId) {
    return find("repository = ?1 and blobId = ?2", repository, blobId).firstResultOptional();
  }

  public long countByRepository(String repository) {
    return count("repository = ?1", repository);
  }

  public long touchByRepositoryAndBlob(String repository, String blobId, Instant cutoff, Instant now) {
    return update(
        "accessedAt = ?1 where repository = ?2 and blobId = ?3"
            + " and (accessedAt is null or accessedAt <= ?4)",
        now, repository, blobId, cutoff);
  }

  /**
   * The distinct content a repository references, with each blob's size — the record table is the
   * one place a size is stored beside the id, so a union costs no filesystem call. Two records may
   * share a blob (two branches may produce pixel-identical goldens), which is exactly why this is
   * distinct rather than a sum over rows.
   */
  public List<Object[]> listDistinctBlobs(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct r.blobId, r.size from ArtifactRecord r"
                + " where r.repository = :repository",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }
}
