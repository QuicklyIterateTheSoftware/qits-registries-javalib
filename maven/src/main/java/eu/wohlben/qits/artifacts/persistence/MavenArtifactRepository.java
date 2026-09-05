package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.MavenArtifactId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link MavenArtifact}, keyed by {@code (repository, path)}. */
@ApplicationScoped
public class MavenArtifactRepository implements PanacheRepositoryBase<MavenArtifact, MavenArtifactId> {

  public Optional<MavenArtifact> findOne(String repository, String path) {
    return findByIdOptional(new MavenArtifactId(repository, path));
  }

  /**
   * Writes one pulled-through file and <b>lets the primary key be the thing that decides</b> whether
   * it was already there.
   *
   * <p>The flush is the whole method. Without it the insert is queued until the transaction commits,
   * which is somebody else's stack frame, and a duplicate arrives there as a rollback nobody local
   * can interpret — the caller needs the violation <em>here</em>, where "that path is already
   * recorded" is a known and harmless outcome. See {@code
   * MavenRegistryService.recordProxiedArtifact}, which runs this in a transaction of its own for
   * exactly that reason.
   *
   * <p>This replaced a {@code findOne(...).isPresent()} guard in front of a {@code persist}, and the
   * difference is not style. Two builds resolving the same new dependency at the same moment is the
   * NORMAL case for a pull-through cache, not the edge — one {@code mvn -T} run does it to itself —
   * and both requests read "absent" before either wrote. What follows is one insert that lands and
   * one that raises {@code duplicate key value violates unique constraint "maven_artifact_pkey"}.
   *
   * <p><b>On 2026-09-05 that was worse than a failed write.</b> A {@code
   * quarkus-proxy-registry-3.34.6.pom} cached during exactly such a race came out of it with the
   * table holding more than one heap tuple for its key, and from then on the access-tracking {@code
   * UPDATE} that every read performs — which touches no key column, so it cannot violate a primary
   * key unless the index has stopped agreeing with the heap — raised that same violation on every
   * single request. The coordinate answered 500 for four days and blocked release gates across the
   * platform. The race is what put it there, so the race is what this closes.
   *
   * <p>Deliberately NOT {@code insert … on conflict do nothing}, which would say this in one
   * statement and no exception: this module's own suite runs its entity tables on H2 (only the blob
   * tables get a real postgres, because only they need one), and a repository that could not be
   * tested on the engine the suite uses would be worse than an exception on a path that is taken
   * once in a thousand pulls.
   *
   * @throws jakarta.persistence.PersistenceException the path is already recorded — the caller's to
   *     recognise and absorb
   */
  public void store(
      String repository, String path, String blobId, long sizeBytes, Instant createdAt) {
    MavenArtifact row = new MavenArtifact();
    row.repository = repository;
    row.path = path;
    row.blobId = blobId;
    row.sizeBytes = sizeBytes;
    row.createdAt = createdAt;
    persist(row);
    flush();
  }

  /**
   * Removes <b>every</b> row at one path, and says how many there were.
   *
   * <p>The twin of {@code findOne(...)} + {@code delete(entity)}, and it exists because that pair
   * cannot clear the fault this cache actually suffered. A load returns one tuple or none; if the
   * primary key has stopped holding — which is what a {@code duplicate key} error on a non-key
   * {@code UPDATE} means — deleting the entity a load returned leaves the other tuple exactly where
   * it was, and the caller is told it succeeded. A bulk delete on the same predicate takes all of
   * them.
   *
   * <p>The count is returned rather than swallowed: more than one row for one primary key is a fact
   * about the store that whoever is reading the log needs, and it is the difference between "this
   * entry was cold" and "this entry was corrupt".
   */
  public long deleteEveryRowAt(String repository, String path) {
    return delete("repository = ?1 and path = ?2", repository, path);
  }

  /** Every path a repository holds, lexically — the enumeration a GC report lists identities from. */
  public List<String> listPaths(String repository) {
    return getEntityManager()
        .createQuery(
            "select a.path from MavenArtifact a where a.repository = :repository order by a.path",
            String.class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * Moves {@code accessed_at} onto one deployed path, but only if the stored value is older than
   * {@code cutoff} — the coalescing, expressed as a predicate rather than as a read-then-write.
   */
  public long touch(String repository, String path, Instant cutoff, Instant now) {
    return update(
        "accessedAt = ?1 where repository = ?2 and path = ?3"
            + " and (accessedAt is null or accessedAt <= ?4)",
        now, repository, path, cutoff);
  }

  /** The deployed files of one repository — the maven meaning of the explorer's one count. */
  public long countByRepository(String repository) {
    return count("repository = ?1", repository);
  }

  /**
   * The distinct blobs a repository references, with their sizes — the maven half of a size union.
   *
   * <p>{@code (blobId, sizeBytes)} pairs, sized from the row rather than from disk: {@code
   * maven_artifact} is the one protocol table whose size was free at stage time, so neither the
   * census nor the explorer needs a disk read or a nullable figure here.
   */
  public List<Object[]> listDistinctBlobs(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct a.blobId, a.sizeBytes from MavenArtifact a"
                + " where a.repository = :repository",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * {@code (path, createdAt)} for every row under a prefix — the whole of what the derived
   * {@code maven-metadata.xml} reads. A prefix scan on the primary key's leading columns.
   */
  public List<Object[]> listPathsAndCreatedAtStartingWith(String repository, String prefix) {
    return getEntityManager()
        .createQuery(
            "select a.path, a.createdAt from MavenArtifact a"
                + " where a.repository = :repository and a.path like :prefix",
            Object[].class)
        .setParameter("repository", repository)
        .setParameter("prefix", prefix + "/%")
        .getResultList();
  }
}
