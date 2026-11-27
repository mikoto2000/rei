package dev.mikoto2000.rei.autonomous;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Installs the draft foundation without registering generation or publication tools. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AutonomousActionProperties.class)
public class AutonomousActionConfiguration {
  @Bean(destroyMethod = "stop")
  AutonomousDraftStore autonomousDraftStore(
      StorageObjectRegistry registry, Clock clock, AutonomousActionProperties settings) {
    var store = new AutonomousDraftStore(new StorageDatabase(registry.root()), clock, settings);
    store.recoverInterruptedConsiderations();
    return store;
  }

  @Bean(destroyMethod = "close")
  AutonomousDraftLifecycle autonomousDraftLifecycle(AutonomousDraftStore store, AgentEventBus bus) {
    return new AutonomousDraftLifecycle(store, bus);
  }
}
