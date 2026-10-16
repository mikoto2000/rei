package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.storage.SqliteEventCursorStore;
import dev.mikoto2000.rei.storage.StorageMigrationConfiguration;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(StorageMigrationConfiguration.class)
public class EventStorageConfiguration {
  @Bean
  public ProjectAgentEventStore projectAgentEventStore(StorageObjectRegistry objects) {
    return new SqliteProjectAgentEventStore(objects.root());
  }

  @Bean
  public SqliteEventCursorStore sqliteEventCursorStore(StorageObjectRegistry objects) {
    return new SqliteEventCursorStore(objects.root());
  }
}
