package dev.mikoto2000.rei.websearch;

import java.util.List;

/**
 * Web 検索と本文取得の統合結果。
 *
 * @param query 検索条件
 * @param results 結果一覧
 * @param assessment 評価
 * @param omissions 省略した内容
 * @param searchQueries 検索検索条件一覧
 * @param pageAttempts ページ試行回数
 */
public record WebSearchAndReadResponse(
    String query,
    List<WebSearchAndReadItem> results,
    WebRetrievalAssessment assessment,
    List<String> omissions,
    int searchQueries,
    int pageAttempts) {

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchAndReadResponse(String query, List<WebSearchAndReadItem> results) {
    this(
        query,
        results,
        WebRetrievalAssessment.unknown("assessment_not_performed"),
        List.of(),
        0,
        0);
  }

  /** 受け取った情報からWebSearchAndReadResponseを作成する。 */
  public WebSearchAndReadResponse {
    results = results == null ? List.of() : List.copyOf(results);
    omissions = omissions == null ? List.of() : List.copyOf(omissions);
  }
}
