package dev.mikoto2000.rei.llm;

/**
 * 用途に応じたモデル利用に必要なモデル呼び出し予算対象範囲を扱う。
 *
 * <p>Lexical budget propagation for synchronous providers, explicitly captured at worker
 * boundaries.
 */
public final class ModelCallBudgetScope implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<ModelCallBudget> CURRENT = new ThreadLocal<>();

  /** 前回。 */
  private final ModelCallBudget previous;

  /** 終了済み。 */
  private boolean closed;

  /** 指定された値や利用先を使って、ModelCallBudgetScopeを初期化する。 */
  private ModelCallBudgetScope(ModelCallBudget budget) {
    previous = CURRENT.get();
    if (budget == null) CURRENT.remove();
    else CURRENT.set(budget);
  }

  /** 必要な対象を利用できるよう、モデル呼び出し予算対象範囲の利用を開始する。 */
  public static ModelCallBudgetScope open(ModelCallBudget budget) {
    return new ModelCallBudgetScope(budget);
  }

  /** 用途に応じたモデル利用で用いる現在を返す。 */
  public static ModelCallBudget current() {
    return CURRENT.get();
  }

  /** 用途に応じたモデル利用で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (closed) return;
    closed = true;
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
