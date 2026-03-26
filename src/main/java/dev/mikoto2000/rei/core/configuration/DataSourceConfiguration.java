package dev.mikoto2000.rei.core.configuration;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

import dev.mikoto2000.rei.core.datasource.ReiPaths;

@Configuration
public class DataSourceConfiguration {

  @Bean
  public DataSource dataSource() throws Exception {
    Path dbPath = ReiPaths.memoryDbPath();
    ReiPaths.ensureParentDirectoryExists(dbPath);

    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + dbPath.toString());
    return dataSource;
  }
}
