package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One immutable metadata record per upload. {@link #blobId} is the SHA-256 (hex) of the content;
 * many records may share a blobId (two branches may legitimately produce pixel-identical goldens),
 * yet each keeps its own distinct row. {@link #createdAt} is server-stamped, never trusted from the
 * wire. The {@link #metadata} map is the flat well-known-plus-opaque string map — unknown keys are
 * legal and queryable.
 */
@Entity
@Table(name = "artifact_record")
public class ArtifactRecord extends PanacheEntityBase {

  @Id public String id;

  @Column(nullable = false)
  public String repository;

  @Column(name = "blob_id", nullable = false, length = 64)
  public String blobId;

  @Column(nullable = false)
  public String mediatype;

  @Column(name = "size_bytes", nullable = false)
  public long size;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "accessed_at")
  public Instant accessedAt;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "artifact_metadata", joinColumns = @JoinColumn(name = "record_id"))
  @MapKeyColumn(name = "meta_key")
  @Column(name = "meta_value", length = 4000)
  public Map<String, String> metadata = new HashMap<>();
}
