package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmPropertiesTest {

  @Test
  void outputLimitReplanBudgetsHaveSafeDefaults() {
    LlmProperties properties = new LlmProperties();

    assertThat(properties.getOutputLimit().getMaxReplansPerGoal()).isEqualTo(2);
    assertThat(properties.getOutputLimit().getMaxSubgoalsPerReplan()).isEqualTo(8);
    assertThat(properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(30);
  }

  @Test
  void outputLimitReplanBudgetsFallbackWhenInvalid() {
    LlmProperties properties = new LlmProperties();
    properties.getOutputLimit().setMaxReplansPerGoal(0);
    properties.getOutputLimit().setMaxSubgoalsPerReplan(-1);
    properties.getOutputLimit().setMaxLlmCallsPerRun(null);

    assertThat(properties.getOutputLimit().getMaxReplansPerGoal()).isEqualTo(2);
    assertThat(properties.getOutputLimit().getMaxSubgoalsPerReplan()).isEqualTo(8);
    assertThat(properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(30);
  }
}
