package dev.mikoto2000.rei.feed;

public record FeedItemSummaryResult(
    String summary,
    String summarySource,
    String articleFetchStatus) {
}
