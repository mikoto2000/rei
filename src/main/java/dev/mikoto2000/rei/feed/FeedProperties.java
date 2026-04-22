package dev.mikoto2000.rei.feed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.feed")
public record FeedProperties(
    int briefingMaxItems) {

  public FeedProperties {
    if (briefingMaxItems <= 0) {
      briefingMaxItems = 20;
    }
  }

  public FeedProperties() {
    this(20);
  }
}
