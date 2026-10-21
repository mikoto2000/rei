package dev.mikoto2000.rei.http;

/**
 * 外部情報の取得に必要な引き継ぎ対象範囲を扱う。
 *
 * <p>Explicit aggregate budget propagation, independent of a waiter's cancellation context.
 */
public final class TransferScope implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<TransferBudget> CURRENT = new ThreadLocal<>();

  /** 前回。 */
  private final TransferBudget previous;

  /** 指定された値や利用先を使って、TransferScopeを初期化する。 */
  private TransferScope(TransferBudget budget) {
    previous = CURRENT.get();
    if (budget == null) CURRENT.remove();
    else CURRENT.set(budget);
  }

  /** 外部情報の取得で用いる現在を返す。 */
  public static TransferBudget current() {
    return CURRENT.get();
  }

  /** 外部情報の取得で用いる利用開始を返す。 */
  public static TransferScope enter(TransferBudget budget) {
    return new TransferScope(budget);
  }

  /** 外部情報の取得に関する機能の接続を処理する。 */
  public static void wire(int bytes) {
    var budget = current();
    if (budget != null) budget.wire(bytes);
  }

  /** 外部情報の取得に関する復元済みを処理する。 */
  public static void decoded(int bytes) {
    var budget = current();
    if (budget != null) budget.decoded(bytes);
  }

  /** 外部情報の取得で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
