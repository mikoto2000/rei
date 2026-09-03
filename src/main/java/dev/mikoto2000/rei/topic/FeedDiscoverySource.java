package dev.mikoto2000.rei.topic;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.feed.FeedService;

@Component
@ConditionalOnBean(FeedService.class)
public class FeedDiscoverySource implements DiscoverySource {
  private final FeedService feedService;

  public FeedDiscoverySource(FeedService feedService) {
    this.feedService = feedService;
  }

  @Override
  public List<DiscoveryItem> discover(DiscoveryContext context) {
    OffsetDateTime now = OffsetDateTime.ofInstant(context.currentTime(), java.time.ZoneOffset.UTC);
    OffsetDateTime from = now.minusDays(30);
    return feedService.listBriefingItems(from, now, 3).stream()
        .flatMap(item -> context.seeds().stream()
            .filter(seed -> matches(seed, item.title() + " " + item.description() + " " + item.content()))
            .map(seed -> new DiscoveryItem(
                "feed:" + item.id(),
                item.title(),
                item.description(),
                item.url(),
                TopicSource.FEED,
                item.publishedAt() == null ? null : item.publishedAt().toInstant(),
                0.80d,
                seed)))
        .toList();
  }

  private boolean matches(String seed, String text) {
    if (seed == null || text == null) return false;
    return text.toLowerCase(java.util.Locale.ROOT).contains(seed.toLowerCase(java.util.Locale.ROOT));
  }
}
