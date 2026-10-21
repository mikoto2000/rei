package dev.mikoto2000.rei.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 検証条件を再現するためのSQLite検証データベース。
 *
 * <p>Real SQLite, including FTS, with one isolated database per property sample.
 */
public final class SqliteTestDatabase implements AutoCloseable {
  /** 検証に使う接続。 */
  private final Connection connection;

  /** 検証に使うデータ取得元。 */
  private final DataSource dataSource;

  /** 検証条件に必要な値を受け取り、SqliteTestDatabaseを用意する。 */
  public SqliteTestDatabase() throws SQLException {
    connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    // JdbcClient closes its logical connections; the fixture owns the physical connection.
    dataSource = new SingleConnectionDataSource(connection, true);
  }

  /** {@code dataSource} の条件を再現するための補助情報を返す。 */
  public DataSource dataSource() {
    return dataSource;
  }

  /** 検証で利用した対象の終了を扱う。 */
  @Override
  public void close() throws SQLException {
    connection.close();
  }
}
