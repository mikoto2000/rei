package dev.mikoto2000.rei.memory.service;

import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.memory.configuration.MemoryConsolidationProperties;

/**
 * 統合モデルの使用量と上限を管理する。
 *
 * <p>Absolute budget shared by extraction and summary in one legacy command invocation.
 */
final class ConsolidationModelBudget implements ModelCallBudget {
  /** 予算。 */
  private final OutputLimitRunBudget budget;

  /** 指定された値や利用先を使って、ConsolidationModelBudgetを初期化する。 */
  ConsolidationModelBudget(MemoryConsolidationProperties properties) {
    budget =
        new OutputLimitRunBudget(
            0,
            properties.maxLlmCalls() == 0 ? Integer.MAX_VALUE : properties.maxLlmCalls(),
            null,
            properties.maxTotalTokens());
  }

  /** 長期記憶の保存と検索の操作を実行する。 */
  public void run() {
    RunCancellation.propagate(null);
    if (budget.tokenExhausted())
      throw new ExecutionStoppedException(
          budget.usageUnknown() ? TOKEN_USAGE_UNKNOWN : TOKEN_BUDGET_EXCEEDED);
    if (!budget.tryConsumeLlmCall()) throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
  }

  /** トークン数による実行予算の制限が有効かどうかを返す。 */
  public boolean tokenLimitEnabled() {
    return budget.tokenLimitEnabled();
  }

  /** 使用したトークン数を実行予算へ記録する。 */
  public void recordTotalTokens(Integer tokens) {
    RunCancellation.propagate(null);
    budget.recordTotalTokens(tokens);
    if (budget.usageUnknown()) throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if (budget.tokenExceeded()) throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
