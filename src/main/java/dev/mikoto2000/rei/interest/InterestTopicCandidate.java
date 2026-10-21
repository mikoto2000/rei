package dev.mikoto2000.rei.interest;

/**
 * 関心の管理で受け渡す関心話題候補を表す。
 *
 * @param topic 話題
 * @param reason 理由
 * @param searchQuery 検索検索条件
 * @param score 評価値
 */
public record InterestTopicCandidate(
    String topic, String reason, String searchQuery, double score) {}
