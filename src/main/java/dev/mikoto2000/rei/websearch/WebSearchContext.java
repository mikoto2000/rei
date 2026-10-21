package dev.mikoto2000.rei.websearch;

import java.util.List;

/**
 * Web上の情報の調査で受け渡すWeb検索文脈を表す。
 *
 * @param primaryResults 主要な結果一覧
 * @param secondaryResults 補助的な結果一覧
 * @param orderedResults ordered結果一覧
 * @param assessment 評価
 * @param omissions 省略した内容
 * @param searchQueries 検索検索条件一覧
 * @param pageAttempts ページ試行回数
 */
public record WebSearchContext(
    List<WebSearchPage> primaryResults,
    List<WebSearchPage> secondaryResults,
    @com.fasterxml.jackson.annotation.JsonIgnore List<WebSearchPage> orderedResults,
    WebRetrievalAssessment assessment,
    List<String> omissions,
    int searchQueries,
    int pageAttempts) {

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchContext(
      List<WebSearchPage> primary, List<WebSearchPage> secondary, List<WebSearchPage> ordered) {
    this(
        primary,
        secondary,
        ordered,
        WebRetrievalAssessment.unknown("assessment_not_performed"),
        List.of(),
        0,
        0);
  }

  /** 受け取った情報からWebSearchContextを作成する。 */
  public WebSearchContext {
    primaryResults = List.copyOf(primaryResults);
    secondaryResults = List.copyOf(secondaryResults);
    orderedResults = List.copyOf(orderedResults);
    omissions = List.copyOf(omissions);
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchContext(
      List<WebSearchPage> primaryResults, List<WebSearchPage> secondaryResults) {
    this(
        primaryResults,
        secondaryResults,
        java.util.stream.Stream.concat(primaryResults.stream(), secondaryResults.stream())
            .toList());
  }

  /** Web上の情報の調査で用いる主要な限定を返す。 */
  public static WebSearchContext primaryOnly(List<WebSearchPage> primaryResults) {
    return new WebSearchContext(primaryResults, List.of());
  }

  /** Web上の情報の調査で用いる全対象結果一覧を返す。 */
  public List<WebSearchPage> allResults() {
    return orderedResults;
  }
}
