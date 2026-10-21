package dev.mikoto2000.rei.core.checkpoint;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Checkpoint Store の Bean 定義。 */
@Configuration(proxyBeanMethods = false)
public class CheckpointConfiguration {

  /** 実行状態の引き継ぎで用いる再開用の記録保存先を返す。 */
  @org.springframework.context.annotation.Scope(
      value = "reiProject",
      proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  @Bean
  public CheckpointStore checkpointStore(
      Clock clock,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventFactory>
          events,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventPublisher>
          publisher) {
    return new CheckpointStore(clock, events.getIfAvailable(), publisher.getIfAvailable());
  }
}
