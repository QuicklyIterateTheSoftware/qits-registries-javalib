package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A named, typed container for blobs. The name is the natural key (repositories are addressed by
 * name in the API); the type selects the validation profile enforced on upload.
 *
 * <p>The type is an <b>opaque key</b>, resolved to a {@link RepositoryTypeProfile} through {@code
 * RepositoryTypeProfiles}. The stored spelling is the screaming-snake one this column has always
 * carried ({@code CI_SCREENSHOTS}, {@code OCI_IMAGES}, …), so existing rows and the services'
 * {@code ck_artifact_repository_type} check constraint keep their meaning; the API's kebab form is
 * {@link RepositoryTypeProfile#wireName()}.
 */
@Entity
@Table(name = "artifact_repository")
public class ArtifactRepository extends PanacheEntityBase {

  @Id public String name;

  @Column(nullable = false)
  public String type;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
