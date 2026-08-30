package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionTombstoneRepository;
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
 * store that holds nothing.
 *
 * <p>Two engines, on purpose, and the same split qits-blobstore's own suite runs: the entity tables
 * are H2 on the default datasource, the blob tables are real postgres on the named one. See {@code
 * src/test/resources/application.properties}.
 *
 * <p>One copy per module rather than a shared test jar — the rule qits-platform-artifacts already
 * followed between its own modules: sharing would mean publishing a test jar and widening a
 * package-private support class across a jar boundary to save a wipe method.
 */
abstract class ArtifactsTestSupport {

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactRepositoryRepository repositories;

  @Inject NpmVersionRepository npmVersions;

  @Inject NpmDistTagRepository npmDistTags;

  @Inject NpmVersionTombstoneRepository npmVersionTombstones;

  @Inject NpmProxyPackumentRepository npmProxyPackuments;

  @Inject
  @DataSource("blobs")
  AgroalDataSource blobs;

  @BeforeEach
  void reset() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // The protocol tables first: every one of them carries a foreign key to
              // artifact_repository.
              npmDistTags.deleteAll();
              npmVersions.deleteAll();
              npmVersionTombstones.deleteAll();
              npmProxyPackuments.deleteAll();
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
