package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

/** 情報への関心実行待ちの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
public class CuriosityQueueConfiguration {

  /** 話題の管理で用いる情報への関心データ取得元を返す。 */
  @Bean
  @Qualifier("curiosityDataSource")
  public DataSource curiosityDataSource() throws Exception {
    var path = ReiPaths.curiosityDbPath();
    ReiPaths.ensureParentDirectoryExists(path);
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + path);
    return dataSource;
  }

  /** 話題の管理で用いる情報への関心実行待ちを返す。 */
  @Bean
  public CuriosityQueue curiosityQueue(@Qualifier("curiosityDataSource") DataSource dataSource) {
    return new SqliteCuriosityQueue(dataSource);
  }
}
