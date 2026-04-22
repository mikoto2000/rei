package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

public record FeedFetchFailure(
    long id,
    long feedId,
    OffsetDateTime failedAt,
    String errorMessage,
    Integer httpStatus) {
}
