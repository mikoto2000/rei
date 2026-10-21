package dev.mikoto2000.rei.memory.configuration;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

/** 記憶データ取得元の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryDataSourceConfiguration {

  /** 長期記憶の準備で用いる記憶統合データ取得元を返す。 */
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
