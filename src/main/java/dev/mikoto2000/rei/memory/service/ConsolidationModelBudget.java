package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.memory.configuration.MemoryConsolidationProperties;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.*;

/** Absolute budget shared by extraction and summary in one legacy command invocation. */
final class ConsolidationModelBudget implements ModelCallBudget {
  private final OutputLimitRunBudget budget;
  ConsolidationModelBudget(MemoryConsolidationProperties properties) {
    budget=new OutputLimitRunBudget(0,properties.maxLlmCalls()==0?Integer.MAX_VALUE:properties.maxLlmCalls(),null,properties.maxTotalTokens());
  }
  public void run() {
    RunCancellation.propagate(null);
    if(budget.tokenExhausted())throw new ExecutionStoppedException(budget.usageUnknown()?TOKEN_USAGE_UNKNOWN:TOKEN_BUDGET_EXCEEDED);
    if(!budget.tryConsumeLlmCall())throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
  }
  public boolean tokenLimitEnabled(){return budget.tokenLimitEnabled();}
  public void recordTotalTokens(Integer tokens) {
    RunCancellation.propagate(null);budget.recordTotalTokens(tokens);
    if(budget.usageUnknown())throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if(budget.tokenExceeded())throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
