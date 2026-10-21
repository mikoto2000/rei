package dev.mikoto2000.rei.websearch;

/**
 * Web上の情報の調査に必要なWebExtraction対象範囲を扱う。
 *
 * <p>Original user query only; external page text never becomes planning instructions.
 */
final class WebExtractionScope implements AutoCloseable {
  /** 現在。 */
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  /** 前回。 */
  private final String previous;

  /** 指定された値や利用先を使って、WebExtractionScopeを初期化する。 */
  private WebExtractionScope(String query) {
    previous = CURRENT.get();
    if (query == null) CURRENT.remove();
    else CURRENT.set(query);
  }

  /** Web上の情報の調査で用いる検索条件を返す。 */
  static String query() {
    return CURRENT.get();
  }

  /** Web上の情報の調査で用いる利用開始を返す。 */
  static WebExtractionScope enter(String query) {
    return new WebExtractionScope(query);
  }

  /** Web上の情報の調査で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
