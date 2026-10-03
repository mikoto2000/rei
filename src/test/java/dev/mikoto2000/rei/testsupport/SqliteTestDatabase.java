package dev.mikoto2000.rei.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Real SQLite, including FTS, with one isolated database per property sample. */
public final class SqliteTestDatabase implements AutoCloseable {
  private final Connection connection;
  private final DataSource dataSource;

  public SqliteTestDatabase() throws SQLException {
    connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    // JdbcClient closes its logical connections; the fixture owns the physical connection.
    dataSource = new SingleConnectionDataSource(connection, true);
  }

  public DataSource dataSource() { return dataSource; }

  @Override public void close() throws SQLException { connection.close(); }
}
