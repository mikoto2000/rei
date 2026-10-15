package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmPropertiesTest {

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

  @Test
  void outputLimitReplanBudgetsHaveSafeDefaults() {
    LlmProperties properties = new LlmProperties();

    assertThat(properties.getOutputLimit().getMaxReplansPerGoal()).isEqualTo(2);
    assertThat(properties.getOutputLimit().getMaxSubgoalsPerReplan()).isEqualTo(8);
    assertThat(properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(120);
  }

  @Test
  void outputLimitReplanBudgetsFallbackWhenInvalid() {
    LlmProperties properties = new LlmProperties();
    properties.getOutputLimit().setMaxReplansPerGoal(0);
    properties.getOutputLimit().setMaxSubgoalsPerReplan(-1);
    properties.getOutputLimit().setMaxLlmCallsPerRun(null);

    assertThat(properties.getOutputLimit().getMaxReplansPerGoal()).isEqualTo(2);
    assertThat(properties.getOutputLimit().getMaxSubgoalsPerReplan()).isEqualTo(8);
    assertThat(properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(120);
  }
}
