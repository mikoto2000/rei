package dev.mikoto2000.rei.core.working;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;

/**
 * Working Set の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class WorkingSetConfiguration {

  @Bean
  public WorkingSet workingSet(Clock clock, AgentEventFactory events, AgentEventBus eventBus) {
    return new WorkingSet(WorkingSet.DEFAULT_MAX_FILES, clock, events, eventBus);
  }
}
