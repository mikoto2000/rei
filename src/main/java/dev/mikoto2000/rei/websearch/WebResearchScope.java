package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.http.TransferBudget;
import dev.mikoto2000.rei.http.TransferScope;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Web上の情報の調査に必要なWeb調査対象範囲を扱う。
 *
 * <p>Finite quotas shared by the initial search and deterministic supplements; no recursive
 * planning.
 */
final class WebResearchScope implements AutoCloseable {
  /** 部分結果を返す検索経路も含めた障害の有無。 */
  private boolean searchFailed;

  /** 現在。 */
  private static final ThreadLocal<WebResearchScope> CURRENT = new ThreadLocal<>();

  /** 所有者。 */
  private final boolean owner;

  /** 作業ルート。 */
  private final WebResearchScope root;

  /** この機能の動作設定。 */
  private final int maxQueries;

  /** 検索条件一覧。 */
  private final Set<String> queries = new LinkedHashSet<>();

  /** 取得。 */
  private final FetchScope fetch;

  /** 引き継ぎ。 */
  private final TransferScope transfer;

  /** 検索APIの呼び出し予算。 */
  private final SearchRequestBudget api;

  /** 指定された値や利用先を使って、WebResearchScopeを初期化する。 */
  private WebResearchScope(WebResearchLimits limits) {
    this.maxQueries = limits.maxQueries();
    owner = CURRENT.get() == null;
    root = owner ? this : CURRENT.get();
    if (owner) {
      CURRENT.set(this);
      fetch = FetchScope.enter(FetchScope.current().withTimeout(limits.timeout()));
      transfer =
          TransferScope.enter(
              TransferScope.current() == null
                  ? new TransferBudget(limits.totalWireBytes(), limits.totalDecodedBytes())
                  : TransferScope.current());
      api = SearchRequestBudget.enter(limits.maxApiCalls());
    } else {
      fetch = null;
      transfer = null;
      api = null;
    }
  }

  /** Web上の情報の調査で用いる利用開始を返す。 */
  static WebResearchScope enter(WebSearchProperties properties) {
    return enter(WebResearchLimits.capture(properties));
  }

  static WebResearchScope enter(WebResearchLimits limits) {
    return new WebResearchScope(limits);
  }

  /** Web上の情報の調査において、登録検索条件に該当するか判定する。 */
  static boolean registerQuery(String query) {
    var scope = CURRENT.get();
    if (scope == null) return true;
    if (scope.queries.contains(query) || scope.queries.size() >= scope.maxQueries) return false;
    scope.queries.add(query);
    return true;
  }

  /** Web上の情報の調査で用いる検索条件件数を返す。 */
  static int queryCount() {
    var scope = CURRENT.get();
    return scope == null ? 0 : scope.queries.size();
  }

  /** Web上の情報の調査で用いる合計検索条件一覧を返す。 */
  int totalQueries() {
    return root.queries.size();
  }

  /** 取得済み候補を返す場合も検索障害の診断を共有する。 */
  static void recordSearchFailure() {
    var scope = CURRENT.get();
    if (scope != null) scope.searchFailed = true;
  }

  /** 調査中の検索障害はスコープの解放後も参照できる。 */
  boolean searchFailed() {
    return root.searchFailed;
  }

  /** Web上の情報の調査で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (owner) {
      api.close();
      transfer.close();
      fetch.close();
      CURRENT.remove();
    }
  }
}
