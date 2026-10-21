package dev.mikoto2000.rei.core.sqlitevec;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;

/** SQLiteベクトル拡張子読み込みの窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SqliteVecExtensionLoaderTest {

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void loadsResolvedExtensionIntoConnection() throws Exception {
    SqliteVecProperties properties = new SqliteVecProperties();
    Connection connection = mock(Connection.class);
    PreparedStatement preparedStatement = mock(PreparedStatement.class);
    when(connection.prepareStatement("SELECT load_extension(?)")).thenReturn(preparedStatement);

    SqliteVecExtensionLoader loader =
        new SqliteVecExtensionLoader(properties, () -> Path.of("/tmp/vec0.so"));

    loader.load(connection);

    verify(preparedStatement).setString(1, Path.of("/tmp/vec0.so").toAbsolutePath().toString());
    verify(preparedStatement).execute();
  }
}
