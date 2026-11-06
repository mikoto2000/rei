package dev.mikoto2000.rei.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** データソースを設定・公開せず、SQL接続を開く操作だけを受け渡す。 */
@FunctionalInterface
public interface SqlConnectionFactory {
  /** 呼び出し元がクローズする接続を開く。 */
  Connection open() throws SQLException;
}
