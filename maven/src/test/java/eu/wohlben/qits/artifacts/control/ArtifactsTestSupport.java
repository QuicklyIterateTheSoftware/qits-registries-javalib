package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;

/**
 * Empties the blob tables and this module's own tables before each test, so every case starts on a
 * store that holds nothing. One copy per module — see the npm module's twin for why it is not
 * shared, and for the two-engine split.
 */
abstract class ArtifactsTestSupport {

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactRepositoryRepository repositories;

  @Inject MavenArtifactRepository mavenArtifacts;

  @Inject MavenProxyMetadataRepository mavenProxyMetadata;

  @Inject
  @DataSource("blobs")
  AgroalDataSource blobs;

  @BeforeEach
  void reset() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              mavenArtifacts.deleteAll();
              mavenProxyMetadata.deleteAll();
              records.deleteAll();
              repositories.deleteAll();
            });
    // blob first, then blob_content: the identity row is what points at the content, and removing
    // the content cascades to every chunk. No foreign key ties either to the entity tables.
    execute("delete from blob");
    execute("delete from blob_content");
  }

  /**
   * Ages a stored blob past the sweep's grace window.
   *
   * <p>The window is measured from {@code stored_at}, and a test's blobs are always seconds old.
   * Backdating the column is the honest way round: it exercises the same clock comparison
   * production runs, instead of configuring the window away.
   */
  void backdate(String blobId, Duration age) {
    update(
        "update blob set stored_at = ? where id = ?",
        statement -> {
          statement.setObject(1, Instant.now().minus(age).atOffset(ZoneOffset.UTC));
          statement.setString(2, blobId);
        });
  }

  /** How many staging areas exist — the assertion that replaces counting temp files. */
  long stagingCount() {
    return count("select count(*) from blob_content where state = 'STAGING'");
  }

  long count(String sql) {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  void execute(String sql) {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  /** Fills in a prepared statement's parameters, the way JDBC makes you. */
  interface Binding {
    void bind(PreparedStatement statement) throws SQLException;
  }

  void update(String sql, Binding binding) {
    try (Connection connection = blobs.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binding.bind(statement);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }
}
