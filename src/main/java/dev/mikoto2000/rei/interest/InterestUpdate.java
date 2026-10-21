package dev.mikoto2000.rei.interest;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 関心の管理で受け渡す関心更新を表す。
 *
 * @param id 識別子
 * @param topic 話題
 * @param reason 理由
 * @param searchQuery 検索検索条件
 * @param summary 要約
 * @param sourceUrls 取得元URL一覧
 * @param createdAt 作成日時
 */
public record InterestUpdate(
    long id,
    String topic,
    String reason,
    String searchQuery,
    String summary,
    List<String> sourceUrls,
    OffsetDateTime createdAt) {}
