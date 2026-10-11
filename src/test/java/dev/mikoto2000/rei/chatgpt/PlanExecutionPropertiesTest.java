package dev.mikoto2000.rei.chatgpt;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecutionPropertiesTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PlanExecutionProperties.class)
  static class Settings {}

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Settings.class);

  @Test
  void startsWithDefaultSettings() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(PlanExecutionProperties.class)).isEqualTo(
          new PlanExecutionProperties(false, 3, 2, 2, 262144, 1048576,
              Duration.ofSeconds(120), false, 0));
    });
  }

  @Test
  void bindsSettingsIncludingRefinementRounds() {
    runner.withPropertyValues(
        "rei.chatgpt-plan.execution.enabled=true",
        "rei.chatgpt-plan.execution.max-iterations=4",
        "rei.chatgpt-plan.execution.max-repairs=1",
        "rei.chatgpt-plan.execution.max-concurrent=1",
        "rei.chatgpt-plan.execution.max-input-bytes=2048",
        "rei.chatgpt-plan.execution.max-output-bytes=4096",
        "rei.chatgpt-plan.execution.timeout=30s",
        "rei.chatgpt-plan.execution.semantic-validation=true",
        "rei.chatgpt-plan.execution.refinement-rounds=2")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PlanExecutionProperties.class)).isEqualTo(
              new PlanExecutionProperties(true, 4, 1, 1, 2048, 4096,
                  Duration.ofSeconds(30), true, 2));
        });
  }
}
