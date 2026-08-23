package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

public record FetchedFeedItem(
    String title,
    String url,
    OffsetDateTime publishedAt,
    String description,
    String content) {

  public FetchedFeedItem(String title, String url, OffsetDateTime publishedAt) {
    this(title, url, publishedAt, null, null);
  }
}
