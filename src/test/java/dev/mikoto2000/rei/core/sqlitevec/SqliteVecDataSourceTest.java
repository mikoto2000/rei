package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** SQLiteベクトルデータ取得元の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SqliteVecDataSourceTest {

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void loadsExtensionOnConnectionAcquisition() throws Exception {
    DataSource delegate = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(delegate.getConnection()).thenReturn(connection);

    SqliteVecExtensionLoader loader = mock(SqliteVecExtensionLoader.class);
    SqliteVecDataSource dataSource = new SqliteVecDataSource(delegate, loader);

    assertSame(connection, dataSource.getConnection());
    verify(loader).load(connection);
  }
}
