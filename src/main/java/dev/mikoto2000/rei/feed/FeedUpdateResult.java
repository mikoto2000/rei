package dev.mikoto2000.rei.feed;

public record FeedUpdateResult(
    long feedId,
    String feedName,
    int addedItems,
    String errorMessage) {
}
