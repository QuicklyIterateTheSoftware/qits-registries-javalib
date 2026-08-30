package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Panache DAO for {@link ArtifactRepository} (keyed by name). The double "Repository" is the
 * codebase's {@code <Entity>Repository} DAO-naming convention colliding with artifacts's own
 * "repository" domain noun.
 */
@ApplicationScoped
public class ArtifactRepositoryRepository
    implements PanacheRepositoryBase<ArtifactRepository, String> {}
