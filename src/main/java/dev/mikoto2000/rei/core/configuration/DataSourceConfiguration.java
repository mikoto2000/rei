package dev.mikoto2000.rei.core.configuration;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.sqlite.SQLiteDataSource;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;

@Configuration
public class DataSourceConfiguration {

  @Bean
  @Primary
  public DataSource dataSource() throws Exception {
    return sqliteDataSource(ReiPaths.memoryDbPath());
  }

  @Bean
  @Qualifier("vectorStoreDataSource")
  public DataSource vectorStoreDataSource(SqliteVecExtensionLoader sqliteVecExtensionLoader) throws Exception {
    return new SqliteVecDataSource(sqliteVecCapableDataSource(ReiPaths.vectorStoreDbPath()), sqliteVecExtensionLoader);
  }

  DataSource dataSource(Path workDirectory) throws Exception {
    return sqliteDataSource(ReiPaths.memoryDbPath(workDirectory));
  }

  DataSource vectorStoreDataSource(Path workDirectory, SqliteVecExtensionLoader sqliteVecExtensionLoader) throws Exception {
    return new SqliteVecDataSource(sqliteVecCapableDataSource(ReiPaths.vectorStoreDbPath(workDirectory)), sqliteVecExtensionLoader);
  }

  private DataSource sqliteDataSource(Path dbPath) throws Exception {
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString());
    return dataSource;
  }

  private DataSource sqliteVecCapableDataSource(Path dbPath) throws Exception {
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString() + "?enable_load_extension=true");
    return dataSource;
  }
}
