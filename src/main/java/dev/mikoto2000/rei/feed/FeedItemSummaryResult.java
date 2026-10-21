package dev.mikoto2000.rei.feed;

/**
 * フィードの購読と収集で受け渡すフィード項目要約結果を表す。
 *
 * @param summary 要約
 * @param summarySource 要約取得元
 * @param articleFetchStatus 記事取得状態
 */
public record FeedItemSummaryResult(
    String summary, String summarySource, String articleFetchStatus) {}
