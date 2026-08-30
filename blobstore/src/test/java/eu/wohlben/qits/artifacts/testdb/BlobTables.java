package eu.wohlben.qits.artifacts.testdb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Applies {@code db/blobstore-tables.sql} — the very file this library tells consumers to copy into
 * their own Flyway lineage — to the embedded postgres.
 *
 * <p><b>The file, not a copy of it.</b> Reference DDL that no build runs is prose, and prose drifts
 * from the code it describes. Reading the shipped resource is what makes the whole suite a test of
 * that file as much as of the store.
 */
public final class BlobTables {

  /** This library's database on the shared instance. No sibling suite may mean the same one. */
  static final String DATABASE = "qits_blobstore_test";

  private static final String DDL = "db/blobstore-tables.sql";

  private static boolean applied;

  private BlobTables() {}

  /**
   * The url for the blob datasource, with the tables in place.
   *
   * <p>Creating the schema here rather than from a test fixture is deliberate: the store sweeps
   * abandoned staging at Quarkus startup, which happens before any {@code @BeforeAll}, and the
   * config source is the only hook that runs earlier still.
   */
  public static synchronized String url() {
    String url = EmbeddedPg.url(DATABASE);
    if (!applied) {
      apply(url);
      applied = true;
    }
    return url;
  }

  private static void apply(String url) {
    try (Connection connection = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement()) {
      sql.execute("drop table if exists blob, blob_chunk, blob_content cascade");
      for (String statement : statements()) {
        sql.execute(statement);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not apply " + DDL, e);
    }
  }

  /** The file's statements, with its comment lines dropped. It carries no dollar-quoted bodies. */
  private static String[] statements() throws IOException {
    try (InputStream in = BlobTables.class.getClassLoader().getResourceAsStream(DDL)) {
      if (in == null) {
        throw new IOException("not on the classpath: " + DDL);
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      String withoutComments =
          text.lines()
              .filter(line -> !line.stripLeading().startsWith("--"))
              .reduce("", (a, b) -> a + "\n" + b);
      return Arrays.stream(withoutComments.split(";"))
          .map(String::strip)
          .filter(statement -> !statement.isEmpty())
          .toArray(String[]::new);
    }
  }
}
