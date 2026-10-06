package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.*;

/** One explicit ownerless invocation or batch, including repair and semantic judgement. */
final class StandaloneSubAgentBudget implements OutputLimitRunBudget.LlmCallReservation {
  static OutputLimitRunBudget.LlmCallReservation create(SubAgentProperties properties) {
    if(properties.getStandaloneMaxLlmCalls()==0&&properties.getStandaloneMaxTotalTokens()==0)return null;
    return new StandaloneSubAgentBudget(properties);
  }
  private final OutputLimitRunBudget budget;
  private StandaloneSubAgentBudget(SubAgentProperties properties) {
    budget=new OutputLimitRunBudget(0,properties.getStandaloneMaxLlmCalls()==0?Integer.MAX_VALUE:
        properties.getStandaloneMaxLlmCalls(),null,properties.getStandaloneMaxTotalTokens());
  }
  public boolean tryReserve() {
    if(budget.tokenExhausted())throw new ExecutionStoppedException(budget.usageUnknown()?TOKEN_USAGE_UNKNOWN:TOKEN_BUDGET_EXCEEDED);
    return budget.tryConsumeLlmCall();
  }
  public int remaining(){return budget.remainingLlmCalls();}
  public boolean tokenLimitEnabled(){return budget.tokenLimitEnabled();}
  public boolean tokenExhausted(){return budget.tokenExhausted();}
  public boolean usageUnknown(){return budget.usageUnknown();}
  public void recordTotalTokens(Integer tokens) {
    budget.recordTotalTokens(tokens);
    if(budget.usageUnknown())throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if(budget.tokenExceeded())throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
