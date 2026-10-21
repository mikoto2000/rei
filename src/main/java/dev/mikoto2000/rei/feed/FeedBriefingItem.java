package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

/**
 * フィードの購読と収集で受け渡すフィード日次案内項目を表す。
 *
 * @param id 識別子
 * @param title タイトル
 * @param url 参照先のURL
 * @param description 説明
 * @param content 内容
 * @param publishedAt 公開日時
 * @param feedName フィード名前
 */
public record FeedBriefingItem(
    long id,
    String title,
    String url,
    String description,
    String content,
    OffsetDateTime publishedAt,
    String feedName) {

  /** フィードの購読と収集を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FeedBriefingItem(
      long id, String title, String url, OffsetDateTime publishedAt, String feedName) {
    this(id, title, url, null, null, publishedAt, feedName);
  }
}
