package dev.mikoto2000.rei.memory.service;

import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;

/** One Sleep invocation: extraction and semantic resolutions share this absolute budget. */
public final class SleepModelBudget implements ModelCallBudget {
  private final OutputLimitRunBudget budget;
  private final Runnable check;

  public SleepModelBudget(MemoryProperties.Sleep properties, Runnable check) {
    this.check = check;
    budget =
        new OutputLimitRunBudget(
            0,
            properties.maxLlmCalls() == 0 ? Integer.MAX_VALUE : properties.maxLlmCalls(),
            null,
            properties.maxTotalTokens());
  }

  public void run() {
    check.run();
    if (budget.tokenExhausted())
      throw new ExecutionStoppedException(
          budget.usageUnknown() ? TOKEN_USAGE_UNKNOWN : TOKEN_BUDGET_EXCEEDED);
    if (!budget.tryConsumeLlmCall()) throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
  }

  public boolean tokenLimitEnabled() {
    return budget.tokenLimitEnabled();
  }

  public void recordTotalTokens(Integer tokens) {
    check.run();
    budget.recordTotalTokens(tokens);
    if (budget.usageUnknown()) throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if (budget.tokenExceeded()) throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
