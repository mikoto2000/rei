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
  private int pending;
  private boolean settled;
  private StandaloneSubAgentBudget(SubAgentProperties properties) {
    budget=new OutputLimitRunBudget(0,properties.getStandaloneMaxLlmCalls()==0?Integer.MAX_VALUE:
        properties.getStandaloneMaxLlmCalls(),null,properties.getStandaloneMaxTotalTokens());
  }
  public synchronized boolean tryReserve() {
    if(budget.tokenExhausted())throw new ExecutionStoppedException(budget.usageUnknown()?TOKEN_USAGE_UNKNOWN:TOKEN_BUDGET_EXCEEDED);
    // Do not start a new wave against usage still being reported by its siblings.
    if(budget.tokenLimitEnabled()&&settled&&pending>0)return false;
    if(!budget.tryConsumeLlmCall())return false;
    if(budget.tokenLimitEnabled()){if(pending==0)settled=false;pending++;}
    return true;
  }
  public int remaining(){return budget.remainingLlmCalls();}
  public boolean tokenLimitEnabled(){return budget.tokenLimitEnabled();}
  public boolean tokenExhausted(){return budget.tokenExhausted();}
  public boolean usageUnknown(){return budget.usageUnknown();}
  public synchronized void recordTotalTokens(Integer tokens) {
    if(budget.tokenLimitEnabled()){pending=Math.max(0,pending-1);settled=true;}
    budget.recordTotalTokens(tokens);
    if(budget.usageUnknown())throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if(budget.tokenExceeded())throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
