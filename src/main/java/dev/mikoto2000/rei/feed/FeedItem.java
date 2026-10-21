package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

/**
 * フィードの購読と収集で受け渡すフィード項目を表す。
 *
 * @param id 識別子
 * @param feedId フィード識別子
 * @param title タイトル
 * @param url 参照先のURL
 * @param description 説明
 * @param content 内容
 * @param publishedAt 公開日時
 * @param fetchedAt 取得日時
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 */
public record FeedItem(
    long id,
    long feedId,
    String title,
    String url,
    String description,
    String content,
    OffsetDateTime publishedAt,
    OffsetDateTime fetchedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
