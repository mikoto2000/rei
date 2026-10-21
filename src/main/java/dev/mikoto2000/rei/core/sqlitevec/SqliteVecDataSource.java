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
  private final DataSource delegate;

  /** 拡張子読み込みの窓口。 */
  private final SqliteVecExtensionLoader extensionLoader;

  /** 指定された値や利用先を使って、SqliteVecDataSourceを初期化する。 */
  public SqliteVecDataSource(DataSource delegate, SqliteVecExtensionLoader extensionLoader) {
    this.delegate = delegate;
    this.extensionLoader = extensionLoader;
  }

  /** 文書検索用の保存基盤で参照する接続を返す。 */
  @Override
  public Connection getConnection() throws SQLException {
    Connection connection = delegate.getConnection();
    extensionLoader.load(connection);
    return connection;
  }

  /** 文書検索用の保存基盤で参照する接続を返す。 */
  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    Connection connection = delegate.getConnection(username, password);
    extensionLoader.load(connection);
    return connection;
  }

  /** 文書検索用の保存基盤で用いる元の形式への復元を返す。 */
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return delegate.unwrap(iface);
  }

  /** 文書検索用の保存基盤において、Wrapper対象かどうかを判定する。 */
  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return delegate.isWrapperFor(iface);
  }

  /** 文書検索用の保存基盤で参照するログ書き込み先を返す。 */
  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  /** 文書検索用の保存基盤で使用するログ書き込み先を設定する。 */
  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    delegate.setLogWriter(out);
  }

  /** 文書検索用の保存基盤で使用するログインタイムアウトを設定する。 */
  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  /** 文書検索用の保存基盤で参照するログインタイムアウトを返す。 */
  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  /** 文書検索用の保存基盤で参照する親ログの出力先を返す。 */
  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }
}
