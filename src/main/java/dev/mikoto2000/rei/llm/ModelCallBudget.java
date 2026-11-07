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

  /** Keeps existing accounting when a callback already implements the budget contract. */
  static ModelCallBudget fromCallback(Runnable beforeModelCall) {
    if (beforeModelCall == null || beforeModelCall instanceof ModelCallBudget)
      return (ModelCallBudget) beforeModelCall;
    return new ModelCallBudget() {
      @Override
      public void run() {
        beforeModelCall.run();
      }

      @Override
      public boolean tokenLimitEnabled() {
        return false;
      }

      @Override
      public void recordTotalTokens(Integer tokens) {}
    };
  }
}
