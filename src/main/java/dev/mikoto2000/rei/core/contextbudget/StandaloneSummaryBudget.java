package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.*;

/** Shared by the history and active-log summaries of one ownerless context projection. */
final class StandaloneSummaryBudget implements ModelCallBudget {
  static ModelCallBudget create(ContextCompressionProperties properties) {
    if(properties.getStandaloneSummaryMaxLlmCalls()==0&&properties.getStandaloneSummaryMaxTotalTokens()==0)return null;
    return new StandaloneSummaryBudget(properties);
  }
  private final OutputLimitRunBudget budget;
  private StandaloneSummaryBudget(ContextCompressionProperties properties) {
    budget=new OutputLimitRunBudget(0,properties.getStandaloneSummaryMaxLlmCalls()==0?Integer.MAX_VALUE:
        properties.getStandaloneSummaryMaxLlmCalls(),null,properties.getStandaloneSummaryMaxTotalTokens());
  }
  public void run() {
    RunCancellation.propagate(null);
    if(budget.tokenExhausted())throw new ExecutionStoppedException(budget.usageUnknown()?TOKEN_USAGE_UNKNOWN:TOKEN_BUDGET_EXCEEDED);
    if(!budget.tryConsumeLlmCall())throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
  }
  public boolean tokenLimitEnabled(){return budget.tokenLimitEnabled();}
  public void recordTotalTokens(Integer tokens) {
    budget.recordTotalTokens(tokens);RunCancellation.propagate(null);
    if(budget.usageUnknown())throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if(budget.tokenExceeded())throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
