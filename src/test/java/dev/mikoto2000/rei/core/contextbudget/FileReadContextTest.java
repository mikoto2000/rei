package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.tooldelivery.FileReadContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class FileReadContextTest {
  private final TokenEstimator estimator = TokenEstimator.conservative();

  private int budget(ContextCompressionProperties properties) {
    return FileReadContext.bulkBudget(
        64000,
        prompt -> ContextBudgetManager.inputLimit(properties, estimator, prompt),
        prompt -> ContextBudgetManager.estimateMessages(prompt.getInstructions(), estimator),
        estimator::text);
  }

  @Test
  void usesActualProjectionAndRestoresNestedScopesWithoutLeakingBudgets() {
    var properties = new ContextCompressionProperties();
    var options = OpenAiChatOptions.builder().model("small").build();
    var raw = new Prompt("x".repeat(500000), options);
    var projected = new Prompt("current request", options);
    try (var outer = FileReadContext.open(FileReadContext.withProjection(raw, projected), 1, "")) {
      int budget = budget(properties);
      assertThat(budget).isEqualTo(64000);
      FileReadContext.used(budget);
      try (var inner = FileReadContext.open(raw, 1, "")) {
        assertThat(FileReadContext.grant()).isNull();
        assertThat(budget(properties)).isZero();
      }
      assertThat(FileReadContext.grant().tokens()).isEqualTo(budget);
    }
    assertThat(FileReadContext.grant()).isNull();
    assertThat(FileReadContext.active()).isFalse();
  }

  @Test
  void respectsModelSpecificLimitOutputReserveSchemaAndSiblingCount() {
    var properties = new ContextCompressionProperties();
    properties.setModelContextLimits(java.util.Map.of("small", 24000));
    var prompt =
        new Prompt(
            "review",
            OpenAiChatOptions.builder().model("small").maxCompletionTokens(10000).build());
    int one;
    try (var scope = FileReadContext.open(prompt, 1, "")) {
      one = budget(properties);
    }
    assertThat(one).isPositive().isLessThan(6000);
    try (var scope = FileReadContext.open(prompt, 2, "")) {
      assertThat(budget(properties)).isLessThan(one / 2);
    }
  }

  @Test
  void capturesNullableToolContextOnceForTheReadScope() {
    var options =
        org.mockito.Mockito.mock(org.springframework.ai.model.tool.ToolCallingChatOptions.class);
    org.mockito.Mockito.when(options.getToolContext()).thenReturn(java.util.Map.of(), null);
    try (var scope = FileReadContext.open(new Prompt("read", options), 1, "")) {
      assertThat(FileReadContext.active()).isTrue();
    }
    org.mockito.Mockito.verify(options).getToolContext();
  }
}
