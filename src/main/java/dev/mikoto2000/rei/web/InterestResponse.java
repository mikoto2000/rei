package dev.mikoto2000.rei.web;

import java.util.List;

/**
 * Web APIによる操作で受け渡す関心応答を表す。
 *
 * @param id 識別子
 * @param topic 話題
 * @param reason 理由
 * @param searchQuery 検索検索条件
 * @param summary 要約
 * @param sourceUrls 取得元URL一覧
 * @param createdAt 作成日時
 */
public record InterestResponse(
    long id,
    String topic,
    String reason,
    String searchQuery,
    String summary,
    List<String> sourceUrls,
    String createdAt) {
  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static InterestResponse from(dev.mikoto2000.rei.interest.InterestUpdate i) {
    return new InterestResponse(
        i.id(),
        i.topic(),
        i.reason(),
        i.searchQuery(),
        i.summary(),
        i.sourceUrls(),
        i.createdAt().toString());
  }
}
