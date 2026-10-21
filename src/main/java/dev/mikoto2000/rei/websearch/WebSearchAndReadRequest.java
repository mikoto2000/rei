package dev.mikoto2000.rei.websearch;

/**
 * Web 検索と上位ページ本文取得の入力。
 *
 * @param query 検索条件
 * @param maxResults 検索結果の件数上限
 * @param readTop 本文を取得する上位件数
 * @param forceRefresh キャッシュの再確認指定
 */
public record WebSearchAndReadRequest(
    String query,
    Integer maxResults,
    Integer readTop,
    @org.springframework.ai.tool.annotation.ToolParam(
            required = false,
            description = "Revalidate cached web sources")
        Boolean forceRefresh) {
  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchAndReadRequest(String query, Integer maxResults, Integer readTop) {
    this(query, maxResults, readTop, null);
  }
}
