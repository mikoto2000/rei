package dev.mikoto2000.rei.externalagent;

import java.util.function.BooleanSupplier;

public interface ExternalAgentExecutor {
  default boolean supportsContinuation() { return false; }
  default boolean supportsContinuation(ExternalAgentRequest.Agent agent){return agent==ExternalAgentRequest.Agent.CODEX && supportsContinuation();}
  ExternalAgentResult execute(ExternalAgentRequest request, BooleanSupplier cancelled);
  default ExternalAgentResult execute(ExternalAgentRequest request,BooleanSupplier cancelled,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if(budget.tokenLimitEnabled())throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    budget.run();return execute(request,cancelled);
  }
}
