package dev.mikoto2000.rei.core.sqlitevec;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;

class SqliteVecExtensionLoaderTest {

  @Test
  void loadsResolvedExtensionIntoConnection() throws Exception {
    SqliteVecProperties properties = new SqliteVecProperties();
    properties.setEnabled(true);
    Connection connection = mock(Connection.class);
    PreparedStatement preparedStatement = mock(PreparedStatement.class);
    when(connection.prepareStatement("SELECT load_extension(?)")).thenReturn(preparedStatement);

    SqliteVecExtensionLoader loader = new SqliteVecExtensionLoader(
        properties,
        () -> Path.of("/tmp/vec0.so"));

    loader.load(connection);

    verify(preparedStatement).setString(1, "/tmp/vec0.so");
    verify(preparedStatement).execute();
  }
}
