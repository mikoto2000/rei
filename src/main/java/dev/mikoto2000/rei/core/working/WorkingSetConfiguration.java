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

  @org.springframework.context.annotation.Scope(value = "reiProject", proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  @Bean
  public WorkingSet workingSet(Clock clock, AgentEventFactory events, AgentEventBus eventBus) {
    var workingSet = new WorkingSet(WorkingSet.DEFAULT_MAX_FILES, clock, events, eventBus);
    workingSet.enablePersistence(dev.mikoto2000.rei.core.project.ProjectStorage.currentDirectory().resolve("working-set/files.json"));
    return workingSet;
  }
}
