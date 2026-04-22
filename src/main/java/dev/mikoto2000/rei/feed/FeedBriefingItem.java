package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

public record FeedBriefingItem(
    long id,
    String title,
    String url,
    OffsetDateTime publishedAt,
    String feedName) {
}
