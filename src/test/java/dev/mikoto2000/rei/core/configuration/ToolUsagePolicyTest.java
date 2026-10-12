package dev.mikoto2000.rei.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ToolUsagePolicyTest {
  @Test void compositionPreservesOwnPromptAndDoesNotDuplicatePolicy() {
    String prompt = ToolUsagePolicy.appendTo("Own instructions");
    assertThat(prompt).startsWith("Own instructions\n\n").contains(ToolUsagePolicy.INSTRUCTIONS);
    assertThat(ToolUsagePolicy.appendTo(prompt)).isEqualTo(prompt);
    assertThat(ToolUsagePolicy.appendTo(null)).isEqualTo(ToolUsagePolicy.INSTRUCTIONS);
  }
}
