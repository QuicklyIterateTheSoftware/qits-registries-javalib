package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

/**
 * Empties the blob tables and both entity tables before each test, so every case starts on a store
 * that holds nothing.
 *
 * <p>Two engines, on purpose: the entity tables are H2 on the default datasource, the blob tables
 * are real postgres on the named one. See src/test/resources/application.properties.
 */
abstract class ArtifactsTestSupport {

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactRepositoryRepository repositories;

  @Inject BlobDiskIndex diskIndex;

  @Inject
  @DataSource("blobs")
  AgroalDataSource blobs;

  @BeforeEach
  void reset() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
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
   * <p>The window is measured from {@code stored_at}, and a test's blobs are always seconds old — so
   * without this every GC case would assert on what was withheld rather than on the reconciliation.
   * Backdating is the honest way round: it exercises the same clock comparison production runs,
   * instead of configuring the window away.
   */
  void backdate(String blobId, Duration age) {
    update(
        "update blob set stored_at = ? where id = ?",
        statement -> {
          statement.setObject(1, Instant.now().minus(age).atOffset(ZoneOffset.UTC));
          statement.setString(2, blobId);
        });
  }

  /** Ages a staging area, so the staging sweep's TTL can be reached without waiting a day. */
  void backdateStaging(java.util.UUID contentId, Duration age) {
    update(
        "update blob_content set started_at = ? where content_id = ?",
        statement -> {
          statement.setObject(1, Instant.now().minus(age).atOffset(ZoneOffset.UTC));
          statement.setObject(2, contentId);
        });
  }

  /** How many staging areas exist — the assertion that replaces counting temp files. */
  long stagingCount() {
    return count("select count(*) from blob_content where state = 'STAGING'");
  }

  /** How many chunk rows exist, over every content. */
  long chunkCount() {
    return count("select count(*) from blob_chunk");
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

  /** The full required-key set for a ci-screenshots upload of the given dimensions. */
  static Map<String, String> screenshotMeta(String branch, String flow, int width, int height) {
    Map<String, String> m = new HashMap<>();
    m.put("git.branch.name", branch);
    m.put("git.commit.hash", "abc123");
    m.put("qits.userflow.name", flow);
    m.put("qits.userflow.hash", "flowhash");
    m.put("qits.display.name", "step 1");
    m.put("qits.diff.hash", "diffhash");
    m.put("media.resolution.width", Integer.toString(width));
    m.put("media.resolution.height", Integer.toString(height));
    return m;
  }

  /** The full required-key set for a ci-videos upload. */
  static Map<String, String> videoMeta(String branch, String flow) {
    Map<String, String> m = new HashMap<>();
    m.put("git.branch.name", branch);
    m.put("git.commit.hash", "abc123");
    m.put("qits.userflow.name", flow);
    m.put("qits.userflow.hash", "flowhash");
    m.put("qits.display.name", "clip 1");
    m.put("qits.diff.hash", "diffhash");
    m.put("media.resolution.length", "12");
    return m;
  }
}
