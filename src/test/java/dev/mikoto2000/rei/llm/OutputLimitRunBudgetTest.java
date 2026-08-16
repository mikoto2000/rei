package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutputLimitRunBudgetTest {

  @Test
  void consumesLlmCallsUntilLimit() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 2);

    assertThat(budget.tryConsumeLlmCall()).isTrue();
    assertThat(budget.tryConsumeLlmCall()).isTrue();
    assertThat(budget.tryConsumeLlmCall()).isFalse();
    assertThat(budget.remainingLlmCalls()).isZero();
    assertThat(budget.hasRemainingLlmCalls()).isFalse();
  }

  @Test
  void consumesReplansUntilLimit() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 10);

    assertThat(budget.tryConsumeReplan()).isTrue();
    assertThat(budget.tryConsumeReplan()).isTrue();
    assertThat(budget.tryConsumeReplan()).isFalse();
  }

  @Test
  void cannotReplanWhenLlmCallsAreExhausted() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 1);
    budget.tryConsumeLlmCall();

    assertThat(budget.tryConsumeReplan()).isFalse();
  }
}
