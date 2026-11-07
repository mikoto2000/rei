package dev.mikoto2000.rei.core.sqlitevec;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** 文書検索用の保存基盤に必要なSQLiteベクトルデータ取得元を扱う。 */
public class SqliteVecDataSource implements DataSource {

  /** 委譲先。 */
  private final SqlSupplier<Connection> connect;

  private final AuthenticatedConnection connectWithCredentials;
  private final SqlFunction<Class<?>, Object> unwrap;
  private final SqlFunction<Class<?>, Boolean> wrapperFor;
  private final SqlSupplier<PrintWriter> readLogWriter;
  private final SqlConsumer<PrintWriter> writeLogWriter;
  private final SqlSupplier<Integer> readLoginTimeout;
  private final SqlConsumer<Integer> writeLoginTimeout;
  private final ParentLogger parentLogger;

  @FunctionalInterface
  private interface SqlSupplier<T> {
    T get() throws SQLException;
  }

  @FunctionalInterface
  private interface SqlFunction<T, R> {
    R apply(T value) throws SQLException;
  }

  @FunctionalInterface
  private interface SqlConsumer<T> {
    void accept(T value) throws SQLException;
  }

  @FunctionalInterface
  private interface AuthenticatedConnection {
    Connection get(String username, String password) throws SQLException;
  }

  @FunctionalInterface
  private interface ParentLogger {
    Logger get() throws SQLFeatureNotSupportedException;
  }

  /** 拡張子読み込みの窓口。 */
  private final SqliteVecExtensionLoader extensionLoader;

  /** 指定された値や利用先を使って、SqliteVecDataSourceを初期化する。 */
  public SqliteVecDataSource(DataSource delegate, SqliteVecExtensionLoader extensionLoader) {
    this.connect = () -> delegate.getConnection();
    this.connectWithCredentials =
        (username, password) -> delegate.getConnection(username, password);
    this.unwrap = type -> delegate.unwrap(type);
    this.wrapperFor = type -> delegate.isWrapperFor(type);
    this.readLogWriter = () -> delegate.getLogWriter();
    this.writeLogWriter = writer -> delegate.setLogWriter(writer);
    this.readLoginTimeout = () -> delegate.getLoginTimeout();
    this.writeLoginTimeout = seconds -> delegate.setLoginTimeout(seconds);
    this.parentLogger = () -> delegate.getParentLogger();
    this.extensionLoader = extensionLoader;
  }

  /** 文書検索用の保存基盤で参照する接続を返す。 */
  @Override
  public Connection getConnection() throws SQLException {
    return initialize(connect.get());
  }

  /** 文書検索用の保存基盤で参照する接続を返す。 */
  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return initialize(connectWithCredentials.get(username, password));
  }

  private Connection initialize(Connection connection) {
    try {
      extensionLoader.load(connection);
      return connection;
    } catch (RuntimeException | Error failure) {
      try {
        connection.close();
      } catch (SQLException | RuntimeException | Error closeFailure) {
        if (failure != closeFailure) failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /** 文書検索用の保存基盤で用いる元の形式への復元を返す。 */
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return iface.cast(unwrap.apply(iface));
  }

  /** 文書検索用の保存基盤において、Wrapper対象かどうかを判定する。 */
  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return wrapperFor.apply(iface);
  }

  /** 文書検索用の保存基盤で参照するログ書き込み先を返す。 */
  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return readLogWriter.get();
  }

  /** 文書検索用の保存基盤で使用するログ書き込み先を設定する。 */
  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    writeLogWriter.accept(out);
  }

  /** 文書検索用の保存基盤で使用するログインタイムアウトを設定する。 */
  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    writeLoginTimeout.accept(seconds);
  }

  /** 文書検索用の保存基盤で参照するログインタイムアウトを返す。 */
  @Override
  public int getLoginTimeout() throws SQLException {
    return readLoginTimeout.get();
  }

  /** 文書検索用の保存基盤で参照する親ログの出力先を返す。 */
  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return parentLogger.get();
  }
}
