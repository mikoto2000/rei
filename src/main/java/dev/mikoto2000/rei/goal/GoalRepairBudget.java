package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;

/**
 * 目標修復の使用量と上限を管理する。
 *
 * <p>A slice of the same durable reservation, leaving calls for a later repair attempt.
 */
public final class GoalRepairBudget implements LlmCallReservation {
  /** 親。 */
  private final LlmCallReservation parent;

  /** allowance／予約済み。 */
  private final int allowance, reserved;

  /** 使用量。 */
  private int used;

  /** 指定された値や利用先を使って、GoalRepairBudgetを初期化する。 */
  public GoalRepairBudget(LlmCallReservation parent, int reserveCalls) {
    if (parent == null || reserveCalls < 0 || reserveCalls > 10)
      throw new IllegalArgumentException("Repair reserve must be 0..10 calls");
    this.parent = parent;
    int remaining = Math.max(0, parent.remaining());
    reserved = Math.min(reserveCalls, Math.max(0, remaining - 1));
    allowance = remaining - reserved;
  }

  /** 目標の達成管理で判断に用いる試行予約の条件を確認する。 */
  public synchronized boolean tryReserve() {
    if (used >= allowance || !parent.tryReserve()) return false;
    used++;
    return true;
  }

  /** 目標の達成管理で用いる残量を返す。 */
  public synchronized int remaining() {
    return Math.max(0, Math.min(allowance - used, parent.remaining()));
  }

  /** 目標の達成管理において、境界Reachedに該当するか判定する。 */
  public synchronized boolean boundaryReached() {
    return reserved > 0
        && used >= allowance
        && parent.remaining() > 0
        && !parent.tokenExhausted()
        && !parent.usageUnknown();
  }

  /** トークン数による実行予算の制限が有効かどうかを返す。 */
  public boolean tokenLimitEnabled() {
    return parent.tokenLimitEnabled();
  }

  /** 目標の達成管理において、トークン残量なしに該当するか判定する。 */
  public boolean tokenExhausted() {
    return parent.tokenExhausted();
  }

  /** 目標の達成管理において、使用状況不明に該当するか判定する。 */
  public boolean usageUnknown() {
    return parent.usageUnknown();
  }

  /** 使用したトークン数を実行予算へ記録する。 */
  public void recordTotalTokens(Integer tokens) {
    parent.recordTotalTokens(tokens);
  }
}
