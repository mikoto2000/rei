package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

public record FeedItem(
    long id,
    long feedId,
    String title,
    String url,
    OffsetDateTime publishedAt,
    OffsetDateTime fetchedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {
}
