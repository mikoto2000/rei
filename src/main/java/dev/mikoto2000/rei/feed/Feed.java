package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

public record Feed(
    long id,
    String url,
    String title,
    String siteUrl,
    String description,
    String displayName,
    boolean enabled,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime lastFetchedAt) {
}
