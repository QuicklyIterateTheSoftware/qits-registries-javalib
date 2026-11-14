package eu.wohlben.qits.blobstore.testdb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
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

  /**
   * The database for suites that run the store on the schema as it stood BEFORE segments — a
   * consumer that bumped the library and has not migrated yet. Its own database, so the segmented
   * suites and these never see each other's tables.
   */
  static final String PRE_SEGMENTS_DATABASE = "qits_blobstore_pre_segments_test";

  /** The frozen pre-segment DDL, kept as a test resource because the shipped file moved on. */
  public static final String PRE_SEGMENTS_DDL = "db/blobstore-tables-pre-segments.sql";

  /** The upgrade a consumer runs on the pre-segment schema — shipped, and applied unedited. */
  public static final String SEGMENTS_UPGRADE = "db/blobstore-segments-upgrade.sql";

  private static boolean applied;

  private static boolean preSegmentsApplied;

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

  /**
   * The url of a database holding the PRE-segment blob tables, for a test profile that points the
   * blob datasource there instead. Applied once per JVM, like {@link #url()}.
   */
  public static synchronized String preSegmentsUrl() {
    String url = EmbeddedPg.url(PRE_SEGMENTS_DATABASE);
    if (!preSegmentsApplied) {
      // Asked of the database, not only of the flag: a profile's overrides may be read in more than
      // one classloader, and a second apply would drop tables a running suite has just upgraded.
      if (!hasBlobTables(url)) {
        apply(url, PRE_SEGMENTS_DDL);
      }
      preSegmentsApplied = true;
    }
    return url;
  }

  private static boolean hasBlobTables(String url) {
    try (Connection connection = EmbeddedPg.connect(url);
        Statement sql = connection.createStatement();
        java.sql.ResultSet row = sql.executeQuery("select to_regclass('blob') is not null")) {
      row.next();
      return row.getBoolean(1);
    } catch (Exception e) {
      throw new IllegalStateException("could not inspect " + url, e);
    }
  }

  private static void apply(String url) {
    apply(url, DDL);
  }

  private static void apply(String url, String ddl) {
    try (Connection connection = EmbeddedPg.connect(url);
        Statement sql = connection.createStatement()) {
      sql.execute("drop table if exists blob, blob_chunk, blob_content, blob_segment cascade");
      sql.execute("drop function if exists blob_open_segment()");
      for (String statement : statements(ddl)) {
        sql.execute(statement);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not apply " + ddl, e);
    }
  }

  /**
   * A DDL resource's statements, with its comment lines dropped — the way a consumer's Flyway would
   * run them, one by one. None of these files carries a dollar-quoted body or a semicolon in a
   * comment-free line that is not a statement end.
   */
  public static String[] statements(String resource) throws IOException {
    try (InputStream in = BlobTables.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("not on the classpath: " + resource);
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
