package dev.mikoto2000.rei.core.recentchanges;

import java.time.OffsetDateTime;

/**
 * 1 件の変更記録。
 *
 * @param path      変更対象のファイルパス
 * @param operation 操作種別（CREATE / EDIT / DELETE など）
 * @param summary   変更の要約
 * @param timestamp 変更日時
 */
public record RecentChange(
    String path,
    String operation,
    String summary,
    OffsetDateTime timestamp) {
}