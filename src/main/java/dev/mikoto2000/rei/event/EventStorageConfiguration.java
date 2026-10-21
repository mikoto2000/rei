package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.storage.SqliteEventCursorStore;
import dev.mikoto2000.rei.storage.StorageMigrationConfiguration;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** イベント保存の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@Import(StorageMigrationConfiguration.class)
public class EventStorageConfiguration {
  /** 実行状況の通知で用いるプロジェクトエージェントイベント保存先を返す。 */
  @Bean
  public ProjectAgentEventStore projectAgentEventStore(StorageObjectRegistry objects) {
    return new SqliteProjectAgentEventStore(objects.root());
  }

  /** 実行状況の通知で用いるSQLiteイベント続きの取得位置保存先を返す。 */
  @Bean
  public SqliteEventCursorStore sqliteEventCursorStore(StorageObjectRegistry objects) {
    return new SqliteEventCursorStore(objects.root());
  }
}
