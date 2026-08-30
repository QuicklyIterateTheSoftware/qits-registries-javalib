package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.InternalServerErrorException;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.runtime.AgroalDataSourceUtil;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place this library reaches a database, and the one place it decides <b>which</b>.
 *
 * <p><b>Why a config key instead of {@code @DataSource("artifacts")}.</b> Agroal's qualifier takes a
 * compile-time constant, so naming one in this jar would force every consumer onto that name — the
 * mistake the old shipped datasource block made, and the reason this library ships no datasource at
 * all. Each consumer already has a datasource of its own ({@code artifacts}, {@code mirror}, …); it
 * names that one in {@code qits.artifacts.blobs-datasource} and the lookup below finds it. The key
 * replaces {@code qits.artifacts.blobs-dir}: same job, one layer down.
 *
 * <p><b>Resolved lazily, not injected.</b> A qualifier lookup at bean-construction time would run
 * before the consumer's datasource is configurable in a test; the first blob touch is late enough
 * for everyone and the reference is cached from then on.
 *
 * <p><b>Blob bytes bypass Hibernate and JTA.</b> Chunk statements are single-statement autocommit
 * work: one connection borrow of microseconds each, so a 1 GiB push never holds one long
 * transaction pinning a connection and bloating the WAL-visibility horizon, and no chunk is ever a
 * megabyte-wide entity in a Hibernate session. Only {@code promote} and {@code delete} open a
 * transaction, and both are short by construction.
 *
 * <p><b>An ambient JTA transaction is honoured, not fought.</b> Quarkus enlists an Agroal
 * connection taken inside {@code @Transactional}, which arrives with autocommit already off — so
 * {@link #inTransaction} commits only the transactions it opened itself. A caller that promotes
 * inside its own transaction therefore holds the advisory lock until that transaction ends, which
 * is why the callers here promote outside their row work, exactly as they did when promote was a
 * file rename.
 */
@ApplicationScoped
class BlobDb {

  /**
   * The consuming service's datasource that reaches the blob tables. {@code <default>} is Quarkus'
   * own name for the unnamed datasource, so a consumer with only one needs no configuration.
   */
  @ConfigProperty(name = "qits.artifacts.blobs-datasource", defaultValue = "<default>")
  String datasourceName;

  private volatile AgroalDataSource resolved;

  /** Work that needs a connection and may fail the way JDBC fails. */
  @FunctionalInterface
  interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  AgroalDataSource dataSource() {
    AgroalDataSource current = resolved;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (resolved == null) {
        resolved = AgroalDataSourceUtil.dataSourceInstance(datasourceName).get();
      }
      return resolved;
    }
  }

  /**
   * Runs one autocommit statement — the shape every chunk read and chunk write takes. The
   * connection is borrowed and returned inside this call, so nothing pins one across a client
   * stall, a JGit pause or a slow docker pull. Content addressing is what makes per-chunk snapshots
   * safe without a transaction spanning them: stored bytes never change.
   */
  <T> T autocommit(String what, SqlWork<T> work) {
    try (Connection connection = dataSource().getConnection()) {
      return work.run(connection);
    } catch (SQLException e) {
      throw new InternalServerErrorException(what, e);
    }
  }

  /**
   * Runs {@code work} in one transaction, committing on return and rolling back on any throw. If
   * the connection arrives already enlisted in a JTA transaction, that transaction owns the commit
   * and this only runs the statements.
   */
  <T> T inTransaction(String what, SqlWork<T> work) {
    try (Connection connection = dataSource().getConnection()) {
      boolean ours = connection.getAutoCommit();
      if (!ours) {
        return work.run(connection);
      }
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        rollbackQuietly(connection);
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new InternalServerErrorException(what, e);
    }
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // The original failure is the one worth reporting; the connection is discarded either way.
    }
  }
}
