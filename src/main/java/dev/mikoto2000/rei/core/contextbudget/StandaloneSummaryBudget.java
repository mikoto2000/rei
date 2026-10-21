package dev.mikoto2000.rei.core.contextbudget;

import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

/**
 * 独立した要約の使用量と上限を管理する。
 *
 * <p>Shared by the history and active-log summaries of one ownerless context projection.
 */
final class StandaloneSummaryBudget implements ModelCallBudget {
  /** 指定された内容から新しい対象を作成する。 */
  static ModelCallBudget create(ContextCompressionProperties properties) {
    if (properties.getStandaloneSummaryMaxLlmCalls() == 0
        && properties.getStandaloneSummaryMaxTotalTokens() == 0) return null;
    return new StandaloneSummaryBudget(properties);
  }

  /** 予算。 */
  private final OutputLimitRunBudget budget;

  /** 指定された値や利用先を使って、StandaloneSummaryBudgetを初期化する。 */
  private StandaloneSummaryBudget(ContextCompressionProperties properties) {
    budget =
        new OutputLimitRunBudget(
            0,
            properties.getStandaloneSummaryMaxLlmCalls() == 0
                ? Integer.MAX_VALUE
                : properties.getStandaloneSummaryMaxLlmCalls(),
            null,
            properties.getStandaloneSummaryMaxTotalTokens());
  }

  /** モデルへ渡す文脈の管理の操作を実行する。 */
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
    budget.recordTotalTokens(tokens);
    RunCancellation.propagate(null);
    if (budget.usageUnknown()) throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if (budget.tokenExceeded()) throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
