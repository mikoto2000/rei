package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.HttpFetchException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 検索要求の使用量と上限を管理する。
 *
 * <p>One quota includes provider attempts, expansions and provider redirects.
 */
final class SearchRequestBudget implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<AtomicInteger> CURRENT = new ThreadLocal<>();

  /** 所有者。 */
  private final boolean owner;

  /** 指定された値や利用先を使って、SearchRequestBudgetを初期化する。 */
  private SearchRequestBudget(int maximum) {
    owner = CURRENT.get() == null;
    if (owner) CURRENT.set(new AtomicInteger(maximum));
  }

  /** Web上の情報の調査で用いる利用開始を返す。 */
  static SearchRequestBudget enter(int maximum) {
    return new SearchRequestBudget(maximum);
  }

  /** Web上の情報の調査において、残量なしに該当するか判定する。 */
  static boolean exhausted() {
    return CURRENT.get() != null && CURRENT.get().get() <= 0;
  }

  /** Web上の情報の調査に関する要求を処理する。 */
  static void request() {
    captureGate().run();
  }

  /** 後から状況を確認できるよう、実行可否の判断を記録する。 */
  static Runnable captureGate() {
    var remaining = CURRENT.get();
    return () -> {
      if (remaining != null && remaining.getAndUpdate(value -> Math.max(0, value - 1)) <= 0)
        throw new HttpFetchException(HttpFetchException.Code.REQUEST_BUDGET);
    };
  }

  /** Web上の情報の調査で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (owner) CURRENT.remove();
  }
}
