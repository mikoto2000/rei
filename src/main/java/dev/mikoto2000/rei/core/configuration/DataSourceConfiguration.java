package dev.mikoto2000.rei.core.configuration;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecDataSource;
import dev.mikoto2000.rei.core.sqlitevec.SqliteVecExtensionLoader;

@Configuration
public class DataSourceConfiguration {

  @Bean
  public DataSource dataSource(SqliteVecExtensionLoader sqliteVecExtensionLoader) throws Exception {
    Path dbPath = ReiPaths.memoryDbPath();
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString() + "?enable_load_extension=true");
    return new SqliteVecDataSource(dataSource, sqliteVecExtensionLoader);
  }
}
