package dev.mikoto2000.rei.core.working;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Working Set の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class WorkingSetConfiguration {

  @Bean
  public WorkingSet workingSet(Clock clock) {
    return new WorkingSet(WorkingSet.DEFAULT_MAX_FILES, clock);
  }
}
