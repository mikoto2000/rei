package dev.mikoto2000.rei.topic;

import java.time.Instant;

public record DiscoveryItem(
    String externalId,
    String title,
    String summary,
    String url,
    TopicSource source,
    Instant publishedAt,
    double relevance,
    String seed) {

  public DiscoveryItem {
    if (externalId == null || externalId.isBlank()) externalId = url;
    if (title == null) title = "";
    if (summary == null) summary = "";
    if (url == null) url = "";
    if (source == null) throw new IllegalArgumentException("source must not be null");
    relevance = Math.max(0.0d, Math.min(1.0d, relevance));
    if (seed == null) seed = "";
  }
}
