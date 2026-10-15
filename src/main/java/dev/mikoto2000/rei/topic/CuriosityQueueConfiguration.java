package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

@Configuration(proxyBeanMethods = false)
public class CuriosityQueueConfiguration {

  @Bean
  @Qualifier("curiosityDataSource")
  public DataSource curiosityDataSource() throws Exception {
    var path = ReiPaths.curiosityDbPath();
    ReiPaths.ensureParentDirectoryExists(path);
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + path);
    return dataSource;
  }

  @Bean
  public CuriosityQueue curiosityQueue(@Qualifier("curiosityDataSource") DataSource dataSource) {
    return new SqliteCuriosityQueue(dataSource);
  }
}
