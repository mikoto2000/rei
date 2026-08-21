package dev.mikoto2000.rei.core.filesummary;

import java.time.OffsetDateTime;

/**
 * 1 ファイルの要約キャッシュエントリ。
 *
 * @param path      ファイルパス
 * @param version   ファイル内容の version（sha256 等）
 * @param summary   ファイルの要約
 * @param createdAt 作成日時
 */
public record FileSummary(
    String path,
    String version,
    String summary,
    OffsetDateTime createdAt) {
}
