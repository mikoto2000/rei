package dev.mikoto2000.rei.externalagent;

import java.util.function.BooleanSupplier;

/** 外部エージェントへの委譲に必要な外部エージェント実行先を扱う。 */
public interface ExternalAgentExecutor {
  /** 外部エージェントへの委譲において、継続入力への対応可否を返す。 */
  default boolean supportsContinuation() {
    return false;
  }

  /** 外部エージェントへの委譲において、継続入力への対応可否を返す。 */
  default boolean supportsContinuation(ExternalAgentRequest.Agent agent) {
    return agent == ExternalAgentRequest.Agent.CODEX && supportsContinuation();
  }

  /** 要求された処理を実行する。 */
  ExternalAgentResult execute(ExternalAgentRequest request, BooleanSupplier cancelled);

  /** 要求された処理を実行する。 */
  default ExternalAgentResult execute(
      ExternalAgentRequest request,
      BooleanSupplier cancelled,
      dev.mikoto2000.rei.llm.ModelCallBudget budget) {
    if (budget.tokenLimitEnabled())
      throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
          dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    budget.run();
    return execute(request, cancelled);
  }
}
