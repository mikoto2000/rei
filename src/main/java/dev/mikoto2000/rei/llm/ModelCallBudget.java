package dev.mikoto2000.rei.llm;

/**
 * モデル呼び出しの使用量と上限を管理する。
 *
 * <p>Optional accounting on the existing before-model callback.
 */
public interface ModelCallBudget extends Runnable {
  /** トークン数による実行予算の制限が有効かどうかを返す。 */
  boolean tokenLimitEnabled();

  /** 使用したトークン数を実行予算へ記録する。 */
  void recordTotalTokens(Integer tokens);
}
