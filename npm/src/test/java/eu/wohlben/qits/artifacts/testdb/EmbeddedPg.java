package eu.wohlben.qits.artifacts.testdb;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One real PostgreSQL for this module's whole surefire JVM.
 *
 * <p><b>Why not a container.</b> The first rule of this repo is that a clone builds and tests green
 * with no docker, and the blob store's only backend is postgres now — so Testcontainers and Quarkus
 * dev services are both out, and the store still has to be exercised against the engine it ships
 * on: {@code pg_advisory_xact_lock}, {@code hashtextextended}, {@code bytea} and a partial index
 * exist on no other. Zonky resolves real postgres binaries as ordinary Maven artifacts and this
 * class spawns them as a child process — a dependency, not a daemon.
 *
 * <p><b>The instance is tracked in a system property, not in the static field alone.</b> A Quarkus
 * test run loads config sources in more than one classloader, so a second copy of this class is
 * loaded with its own statics; the property is the one thing they share, and it is what keeps the
 * count at one postgres per JVM instead of one per classloader.
 *
 * <p>Copied into each module rather than shared: the format modules share no test classpath, which
 * is the house rule this repository already followed for {@code ArtifactsTestSupport}. The database
 * name in {@link BlobTables} is this module's alone, so no sibling suite on one host — and no other
 * module of this reactor — can mean the same one.
 */
public final class EmbeddedPg {

  /** Zonky's superuser. Its authentication is `trust`, so the password below is a placeholder. */
  public static final String USER = "postgres";

  /** Any string does: the embedded instance trusts local connections. Never a real credential. */
  public static final String PASSWORD = "embedded";

  /** Where the running instance's port is published for the other classloaders. */
  private static final String PORT_PROPERTY = "qits.test.embedded-pg.port";

  private static EmbeddedPostgres started;

  private EmbeddedPg() {}

  /** The port the one embedded instance listens on, starting it on the first call. */
  public static synchronized int port() {
    String recorded = System.getProperty(PORT_PROPERTY);
    if (recorded != null) {
      return Integer.parseInt(recorded);
    }
    try {
      // Zonky's default startup wait is 10s of WALL time, and a CI host draining a full run queue
      // spends more than that between initdb and "ready" — measured twice on 2026-09-01, each time
      // as this ConfigSource "could not be instantiated" with zero tests run and the cause buried
      // in a dumpstream nobody can read. A minute is patience, not a hang: a postgres that cannot
      // start at all still fails, just with the real error.
      started = EmbeddedPostgres.builder().setPGStartupWait(java.time.Duration.ofSeconds(60)).start();
    } catch (Exception e) {
      // Printed BEFORE the throw, deliberately: this runs while MicroProfile config sources load,
      // so the throw surfaces as ServiceConfigurationError "could not be instantiated" with the
      // cause visible only in a dumpstream file inside a container that is already gone — measured
      // three times on 2026-09-01. Stderr reaches surefire's output and the step's recorded tail.
      System.err.println("embedded postgres failed to start:");
      e.printStackTrace();
      throw new IllegalStateException("could not start the embedded postgres", e);
    }
    System.setProperty(PORT_PROPERTY, String.valueOf(started.getPort()));
    Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPg::stop, "embedded-pg-stop"));
    return started.getPort();
  }

  /** A JDBC url for the named database on the embedded instance, creating it if it is new. */
  public static synchronized String url(String database) {
    String url = "jdbc:postgresql://localhost:" + port() + "/" + database;
    ensureDatabase(database);
    return url;
  }

  private static String adminUrl() {
    return "jdbc:postgresql://localhost:" + port() + "/postgres";
  }

  private static void ensureDatabase(String database) {
    try (Connection admin = DriverManager.getConnection(adminUrl(), USER, PASSWORD);
        Statement sql = admin.createStatement()) {
      try (ResultSet found =
          sql.executeQuery("select 1 from pg_database where datname = '" + database + "'")) {
        if (found.next()) {
          return;
        }
      }
      sql.execute("create database " + database);
    } catch (Exception e) {
      throw new IllegalStateException("could not create the test database " + database, e);
    }
  }

  private static synchronized void stop() {
    if (started != null) {
      try {
        started.close();
      } catch (Exception e) {
        // A JVM on its way out; a postgres that outlives it by a moment is not worth a stack trace.
      }
      started = null;
    }
  }
}
