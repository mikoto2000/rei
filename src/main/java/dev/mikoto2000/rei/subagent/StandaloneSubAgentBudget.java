package dev.mikoto2000.rei.subagent;

import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

/**
 * 独立した補助エージェントの使用量と上限を管理する。
 *
 * <p>One explicit ownerless invocation or batch, including repair and semantic judgement.
 */
final class StandaloneSubAgentBudget implements OutputLimitRunBudget.LlmCallReservation {
  /** 指定された内容から新しい対象を作成する。 */
  static OutputLimitRunBudget.LlmCallReservation create(SubAgentProperties properties) {
    if (properties.getStandaloneMaxLlmCalls() == 0 && properties.getStandaloneMaxTotalTokens() == 0)
      return null;
    return new StandaloneSubAgentBudget(properties);
  }

  /** 予算。 */
  private final OutputLimitRunBudget budget;

  /** 待機中。 */
  private int pending;

  /** 結果が確定したかどうか。 */
  private boolean settled;

  /** 指定された値や利用先を使って、StandaloneSubAgentBudgetを初期化する。 */
  private StandaloneSubAgentBudget(SubAgentProperties properties) {
    budget =
        new OutputLimitRunBudget(
            0,
            properties.getStandaloneMaxLlmCalls() == 0
                ? Integer.MAX_VALUE
                : properties.getStandaloneMaxLlmCalls(),
            null,
            properties.getStandaloneMaxTotalTokens());
  }

  /** 補助エージェントへの委譲で判断に用いる試行予約の条件を確認する。 */
  public synchronized boolean tryReserve() {
    if (budget.tokenExhausted())
      throw new ExecutionStoppedException(
          budget.usageUnknown() ? TOKEN_USAGE_UNKNOWN : TOKEN_BUDGET_EXCEEDED);
    // Do not start a new wave against usage still being reported by its siblings.
    if (budget.tokenLimitEnabled() && settled && pending > 0) return false;
    if (!budget.tryConsumeLlmCall()) return false;
    if (budget.tokenLimitEnabled()) {
      if (pending == 0) settled = false;
      pending++;
    }
    return true;
  }

  /** 補助エージェントへの委譲で用いる残量を返す。 */
  public int remaining() {
    return budget.remainingLlmCalls();
  }

  /** トークン数による実行予算の制限が有効かどうかを返す。 */
  public boolean tokenLimitEnabled() {
    return budget.tokenLimitEnabled();
  }

  /** 補助エージェントへの委譲において、トークン残量なしに該当するか判定する。 */
  public boolean tokenExhausted() {
    return budget.tokenExhausted();
  }

  /** 補助エージェントへの委譲において、使用状況不明に該当するか判定する。 */
  public boolean usageUnknown() {
    return budget.usageUnknown();
  }

  /** 使用したトークン数を実行予算へ記録する。 */
  public synchronized void recordTotalTokens(Integer tokens) {
    if (budget.tokenLimitEnabled()) {
      pending = Math.max(0, pending - 1);
      settled = true;
    }
    budget.recordTotalTokens(tokens);
    if (budget.usageUnknown()) throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if (budget.tokenExceeded()) throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }
}
