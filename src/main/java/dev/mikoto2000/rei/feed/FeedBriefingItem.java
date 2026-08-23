package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

public record FeedBriefingItem(
    long id,
    String title,
    String url,
    String description,
    String content,
    OffsetDateTime publishedAt,
    String feedName) {

  public FeedBriefingItem(long id, String title, String url, OffsetDateTime publishedAt, String feedName) {
    this(id, title, url, null, null, publishedAt, feedName);
  }
}
