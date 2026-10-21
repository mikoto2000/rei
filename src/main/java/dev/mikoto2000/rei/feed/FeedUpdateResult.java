package dev.mikoto2000.rei.feed;

/**
 * フィードの購読と収集で受け渡すフィード更新結果を表す。
 *
 * @param feedId フィード識別子
 * @param feedName フィード名前
 * @param addedItems 追加済み項目一覧
 * @param errorMessage エラーメッセージ
 */
public record FeedUpdateResult(long feedId, String feedName, int addedItems, String errorMessage) {}
