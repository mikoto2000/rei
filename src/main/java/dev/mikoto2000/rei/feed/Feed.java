package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

/**
 * フィードの購読と収集で受け渡すフィードを表す。
 *
 * @param id 識別子
 * @param url 参照先のURL
 * @param title タイトル
 * @param siteUrl サイトURL
 * @param description 説明
 * @param displayName 表示名前
 * @param enabled 有効状態
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 * @param lastFetchedAt 直近取得日時
 */
public record Feed(
    long id,
    String url,
    String title,
    String siteUrl,
    String description,
    String displayName,
    boolean enabled,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime lastFetchedAt) {}
