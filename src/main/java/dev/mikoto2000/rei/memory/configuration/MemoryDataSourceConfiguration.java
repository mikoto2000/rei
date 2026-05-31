package dev.mikoto2000.rei.memory.configuration;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

import dev.mikoto2000.rei.core.datasource.ReiPaths;

@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryDataSourceConfiguration {

  @Bean
  @Qualifier("memoryConsolidationDataSource")
  public DataSource memoryConsolidationDataSource() throws Exception {
    var path = ReiPaths.memoryConsolidationDbPath();
    ReiPaths.ensureParentDirectoryExists(path);
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + path);
    return dataSource;
  }
}
