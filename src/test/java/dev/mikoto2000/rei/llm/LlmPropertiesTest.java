package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 言語モデル設定の結果と状態の契約を検証するテスト。 */
class LlmPropertiesTest {

  @Test
  void readTimeoutHasFiniteDefaultAndBindsDuration() {
    assertThat(new LlmProperties().getReadTimeout()).isEqualTo(java.time.Duration.ofMinutes(5));
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            java.util.Map.of("rei.llm.read-timeout", "8m"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind("rei.llm", LlmProperties.class)
            .get();
    assertThat(properties.getReadTimeout()).isEqualTo(java.time.Duration.ofMinutes(8));
    for (var invalid :
        java.util.List.of(
            java.time.Duration.ZERO,
            java.time.Duration.ofMillis(-1),
            java.time.Duration.ofNanos(1),
            java.time.Duration.ofHours(2)))
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties.setReadTimeout(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties.setReadTimeout(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void callBudgetDefaultsToEnabledAndBindsFalse() {
    assertThat(new LlmProperties().getOutputLimit().isLlmCallBudgetEnabled()).isTrue();
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            java.util.Map.of("rei.llm.output-limit.llm-call-budget-enabled", "false"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind("rei.llm", LlmProperties.class)
            .get();
    assertThat(properties.getOutputLimit().isLlmCallBudgetEnabled()).isFalse();
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void outputLimitReplanBudgetsHaveSafeDefaults() {
    LlmProperties properties = new LlmProperties();

    assertThat(properties.getOutputLimit().getMaxReplansPerGoal()).isEqualTo(2);
    assertThat(properties.getOutputLimit().getMaxSubgoalsPerReplan()).isEqualTo(8);
    assertThat(properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(120);
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void outputLimitReplanBudgetsFallbackWhenInvalid() {
    LlmProperties properties = new LlmProperties();
    properties.updateOutputLimit(limits -> limits.setMaxReplansPerGoal(0));
    properties.updateOutputLimit(limits -> limits.setMaxSubgoalsPerReplan(-1));
    properties.updateOutputLimit(limits -> limits.setMaxLlmCallsPerRun(null));

    assertThat(properties.getOutputLimit().getMaxReplansPerGoal()).isEqualTo(2);
    assertThat(properties.getOutputLimit().getMaxSubgoalsPerReplan()).isEqualTo(8);
    assertThat(properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(120);
  }
  @Test
  void boundFeaturesAndOutputLimitsAreDefensiveCopies() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            java.util.Map.of(
                "rei.llm.features.chat.model", "configured-model",
                "rei.llm.output-limit.max-total-tokens-per-run", "20"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind("rei.llm", LlmProperties.class)
            .get();
    properties.getFeatures().get("chat").setModel("discarded");
    properties.getOutputLimit().setMaxTotalTokensPerRun(1);
    assertThat(properties.feature("chat").getModel()).isEqualTo("configured-model");
    assertThat(properties.getOutputLimit().getMaxTotalTokensPerRun()).isEqualTo(20);

    var replacement = properties.feature("chat");
    replacement.setModel("replacement");
    properties.setFeature("chat", replacement);
    replacement.setModel("discarded");
    assertThat(properties.feature("chat").getModel()).isEqualTo("replacement");
  }

}
