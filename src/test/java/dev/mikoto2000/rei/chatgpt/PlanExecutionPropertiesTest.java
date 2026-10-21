package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 計画実行設定の結果と状態の契約を検証するテスト。 */
class PlanExecutionPropertiesTest {
  /** 検証条件を再現するための設定。 */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PlanExecutionProperties.class)
  static class Settings {}

  /** 検証に使う実行の担当。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Settings.class);

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void startsWithDefaultSettings() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(PlanExecutionProperties.class))
              .isEqualTo(
                  new PlanExecutionProperties(
                      false, 3, 2, 2, 262144, 1048576, Duration.ofMinutes(10), false, 0));
        });
  }

  /** 長い合計期限一覧を受け付けられることを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"10m", "20m", "30m", "120s"})
  void acceptsLongTotalDeadlinesAndExistingShortLimits(String timeout) {
    runner
        .withPropertyValues("rei.chatgpt-plan.execution.timeout=" + timeout)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(PlanExecutionProperties.class).timeout())
                  .isEqualTo(
                      org.springframework.boot.convert.DurationStyle.detectAndParse(timeout));
            });
  }

  /** 不正なを受け付けないことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"0s", "-1s", "1801s", "31m"})
  void rejectsInvalidOrUnboundedDeadlinesWithTheExactSettingName(String timeout) {
    runner
        .withPropertyValues("rei.chatgpt-plan.execution.timeout=" + timeout)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage(
                      "rei.chatgpt-plan.execution.timeout must be positive and at most 30m");
            });
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void bindsSettingsIncludingRefinementRounds() {
    runner
        .withPropertyValues(
            "rei.chatgpt-plan.execution.enabled=true",
            "rei.chatgpt-plan.execution.max-iterations=4",
            "rei.chatgpt-plan.execution.max-repairs=1",
            "rei.chatgpt-plan.execution.max-concurrent=1",
            "rei.chatgpt-plan.execution.max-input-bytes=2048",
            "rei.chatgpt-plan.execution.max-output-bytes=4096",
            "rei.chatgpt-plan.execution.timeout=30s",
            "rei.chatgpt-plan.execution.semantic-validation=true",
            "rei.chatgpt-plan.execution.refinement-rounds=2")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(PlanExecutionProperties.class))
                  .isEqualTo(
                      new PlanExecutionProperties(
                          true, 4, 1, 1, 2048, 4096, Duration.ofSeconds(30), true, 2));
            });
  }
}
