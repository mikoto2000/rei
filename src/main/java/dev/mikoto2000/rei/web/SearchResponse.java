package dev.mikoto2000.rei.web;

import java.util.List;

/**
 * Web APIによる操作で受け渡す検索応答を表す。
 *
 * @param query 検索条件
 * @param vectorResults ベクトル結果一覧
 * @param webResults Web結果一覧
 */
public record SearchResponse(String query, List<VectorHit> vectorResults, List<WebHit> webResults) {
  /**
   * Web APIによる操作で受け渡すベクトル検索結果を表す。
   *
   * @param docId 文書の識別子
   * @param source 取得元
   * @param chunkIndex 文書の分割単位位置
   * @param score 評価値
   * @param snippet 抜粋
   */
  public record VectorHit(
      String docId, String source, int chunkIndex, Double score, String snippet) {}

  /**
   * Web APIによる操作で受け渡すWeb検索結果を表す。
   *
   * @param title タイトル
   * @param url 参照先のURL
   * @param snippet 抜粋
   * @param publishedAt 公開日時
   * @param content 内容
   * @param truncated 省略の有無
   */
  public record WebHit(
      String title,
      String url,
      String snippet,
      String publishedAt,
      String content,
      boolean truncated) {}

  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static SearchResponse from(dev.mikoto2000.rei.search.SearchKnowledgeResult r) {
    return new SearchResponse(
        r.query(),
        r.vectorResults().stream()
            .map(v -> new VectorHit(v.docId(), v.source(), v.chunkIndex(), v.score(), v.snippet()))
            .toList(),
        r.webContext().allResults().stream()
            .map(
                w ->
                    new WebHit(
                        w.title(),
                        w.url(),
                        w.snippet(),
                        w.publishedAt(),
                        w.content(),
                        w.truncated()))
            .toList());
  }
}
