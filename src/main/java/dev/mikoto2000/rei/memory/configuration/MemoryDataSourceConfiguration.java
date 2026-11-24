package dev.mikoto2000.rei.memory.configuration;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import java.io.IOException;
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

  /** Initialize protocol storage only when its explicit persistence or maintenance path is used. */
  @Bean
  @org.springframework.context.annotation.Lazy
  public dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore modelMessagePayloadStore(
      @Qualifier("memoryConsolidationDataSource") DataSource source) {
    return new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
  }

  /** 長期記憶の準備で用いる記憶統合データ取得元を返す。 */
  @Bean
  @Qualifier("memoryConsolidationDataSource")
  public DataSource memoryConsolidationDataSource() throws IOException {
    var path = ReiPaths.memoryConsolidationDbPath();
    ReiPaths.ensureParentDirectoryExists(path);
    var config = new org.sqlite.SQLiteConfig();
    config.setBusyTimeout(5000);
    SQLiteDataSource dataSource = new SQLiteDataSource(config);
    dataSource.setUrl("jdbc:sqlite:" + path);
    return dataSource;
  }
}
