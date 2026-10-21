package dev.mikoto2000.rei.websearch;

/**
 * Web上の情報の調査で受け渡すWeb取得元別名を表す。
 *
 * <p>Citation metadata remains untrusted source data.
 *
 * @param url 参照先のURL
 * @param title タイトル
 * @param publishedAt 公開日時
 * @param evidenceType 根拠種別
 */
public record WebSourceAlias(String url, String title, String publishedAt, String evidenceType) {
  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSourceAlias(String url, String title, String publishedAt) {
    this(url, title, publishedAt, "search_metadata");
  }

  /** Web上の情報の調査で利用する情報を、受け取った形式から変換する。 */
  public static WebSourceAlias from(WebSearchResult result) {
    return new WebSourceAlias(result.url(), result.title(), result.publishedAt());
  }
}
