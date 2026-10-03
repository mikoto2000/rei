package dev.mikoto2000.rei.core.checkpoint;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Checkpoint Store の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class CheckpointConfiguration {

  @org.springframework.context.annotation.Scope(value = "reiProject", proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  @Bean
  public CheckpointStore checkpointStore(Clock clock,org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventFactory> events,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventPublisher> publisher) {
    return new CheckpointStore(clock,events.getIfAvailable(),publisher.getIfAvailable());
  }
}
