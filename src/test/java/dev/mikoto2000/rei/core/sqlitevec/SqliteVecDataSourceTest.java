package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class SqliteVecDataSourceTest {

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
