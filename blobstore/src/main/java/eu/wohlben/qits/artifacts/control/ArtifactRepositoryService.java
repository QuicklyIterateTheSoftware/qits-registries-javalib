package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/** Lifecycle of the named, typed blob containers. */
@ApplicationScoped
public class ArtifactRepositoryService {

  @Inject ArtifactRepositoryRepository repositories;

  @Inject RepositoryTypeProfiles repositoryTypes;

  /**
   * Idempotently ensures a repository of the given type exists. Re-ensuring an existing repository
   * is a no-op that returns it; requesting a <em>different</em> type for an existing name is a 400
   * (a repository's type is immutable — its stored blobs were validated against it).
   *
   * <p><b>And it holds through a short database outage.</b> {@link DbRetry#inNewTx} owns the
   * transaction here, once per attempt, and replaces the {@code @Transactional} that stood in its
   * place — a joined transaction is not one the retry could open again. Every caller opens none:
   * the ensure endpoint is a plain resource method, and the startup seeder only activates a request
   * context. The body is database work and an in-memory profile lookup, nothing else, which is the
   * rule the retry rests on.
   *
   * <p><b>The flush is what makes the retry reach the insert.</b> Hibernate would otherwise write
   * the new row at commit — the one round trip nobody can place, and the one {@code inNewTx}
   * reports rather than repeats. Flushing last moves it into the statement phase, where a severed
   * connection is a certain no-commit.
   *
   * @param type the stored profile key, e.g. {@code CI_SCREENSHOTS}. A key no contributed profile
   *     claims is a 400 here rather than a row nothing can enforce later.
   */
  public ArtifactRepository ensure(String name, String type) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("repository name is required");
    }
    if (type == null || type.isBlank()) {
      throw new BadRequestException("repository type is required");
    }
    repositoryTypes.require(type);
    return DbRetry.inNewTx(
        "artifacts repository ensure for " + name,
        () -> {
          ArtifactRepository existing = repositories.findById(name);
          if (existing != null) {
            if (!existing.type.equals(type)) {
              throw new BadRequestException(
                  "Repository '" + name + "' already exists with type " + existing.type);
            }
            return existing;
          }
          ArtifactRepository repo = new ArtifactRepository();
          repo.name = name;
          repo.type = type;
          repo.createdAt = Instant.now();
          repositories.persist(repo);
          repositories.getEntityManager().flush();
          return repo;
        });
  }

  public List<ArtifactRepository> list() {
    return repositories.listAll();
  }

  /**
   * Resolves a repository or fails with 404 — the guard on every upload/query/serve path.
   *
   * <p>Wrapped in {@link DbRetry#call} because it is the first database touch of every read and
   * every upload: without it a postgres cutover answers "no such repository" for a repository that
   * exists, which is a 404 a caller acts on. A plain read, so re-running it is free, and it opens
   * no transaction of its own — {@link BlobService} and {@link ArtifactQueryService}, the only
   * callers, open none either.
   */
  public ArtifactRepository require(String name) {
    return DbRetry.call(
        "artifacts repository lookup for " + name,
        () -> {
          ArtifactRepository repo = repositories.findById(name);
          if (repo == null) {
            throw new NotFoundException("No such artifacts repository: " + name);
          }
          return repo;
        });
  }
}
