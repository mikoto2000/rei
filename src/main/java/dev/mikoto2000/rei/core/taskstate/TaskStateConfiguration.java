package dev.mikoto2000.rei.core.taskstate;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Task State の Bean 定義。 */
@Configuration(proxyBeanMethods = false)
public class TaskStateConfiguration {

  /** 作業状態の記録で用いるタスク状態を返す。 */
  @org.springframework.context.annotation.Scope(
      value = "reiProject",
      proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
  @Bean
  public TaskState taskState(Clock clock) {
    return new TaskState(TaskState.DEFAULT_MAX_ITEMS);
  }
}
