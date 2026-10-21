package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

/**
 * フィードの購読と収集で受け渡す取得フィード項目を表す。
 *
 * @param title タイトル
 * @param url 参照先のURL
 * @param publishedAt 公開日時
 * @param description 説明
 * @param content 内容
 */
public record FetchedFeedItem(
    String title, String url, OffsetDateTime publishedAt, String description, String content) {

  /** フィードの購読と収集を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FetchedFeedItem(String title, String url, OffsetDateTime publishedAt) {
    this(title, url, publishedAt, null, null);
  }
}
