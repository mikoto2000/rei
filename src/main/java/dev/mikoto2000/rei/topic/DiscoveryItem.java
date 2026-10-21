package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す利用先の検出項目を表す。
 *
 * @param externalId 外部識別子
 * @param title タイトル
 * @param summary 要約
 * @param url 参照先のURL
 * @param source 取得元
 * @param publishedAt 公開日時
 * @param relevance 関連度
 * @param seed 生成の初期値
 */
public record DiscoveryItem(
    String externalId,
    String title,
    String summary,
    String url,
    TopicSource source,
    Instant publishedAt,
    double relevance,
    String seed) {

  /** 受け取った情報からDiscoveryItemを作成する。 */
  public DiscoveryItem {
    if (externalId == null || externalId.isBlank()) externalId = url;
    if (title == null) title = "";
    if (summary == null) summary = "";
    if (url == null) url = "";
    if (source == null) throw new IllegalArgumentException("source must not be null");
    relevance = Math.max(0.0d, Math.min(1.0d, relevance));
    if (seed == null) seed = "";
  }
}
