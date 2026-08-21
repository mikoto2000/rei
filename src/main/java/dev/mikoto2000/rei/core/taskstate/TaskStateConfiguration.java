package dev.mikoto2000.rei.core.taskstate;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task State の Bean 定義。
 */
@Configuration(proxyBeanMethods = false)
public class TaskStateConfiguration {

  @Bean
  public TaskState taskState(Clock clock) {
    return new TaskState(TaskState.DEFAULT_MAX_ITEMS);
  }
}
