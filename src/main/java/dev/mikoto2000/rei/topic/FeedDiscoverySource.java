package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.feed.FeedService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** 話題の管理に必要なフィード利用先の検出取得元を扱う。 */
@Component
@ConditionalOnBean(FeedService.class)
public class FeedDiscoverySource implements DiscoverySource {
  /** フィードサービス。 */
  private final FeedService feedService;

  /** 指定された値や利用先を使って、FeedDiscoverySourceを初期化する。 */
  public FeedDiscoverySource(FeedService feedService) {
    this.feedService = feedService;
  }

  /** 話題の管理で用いる利用先の検出を返す。 */
  @Override
  public List<DiscoveryItem> discover(DiscoveryContext context) {
    OffsetDateTime now = OffsetDateTime.ofInstant(context.currentTime(), java.time.ZoneOffset.UTC);
    OffsetDateTime from = now.minusDays(30);
    return feedService.listBriefingItems(from, now, 3).stream()
        .flatMap(
            item ->
                context.seeds().stream()
                    .filter(
                        seed ->
                            matches(
                                seed,
                                item.title() + " " + item.description() + " " + item.content()))
                    .map(
                        seed ->
                            new DiscoveryItem(
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

  /** 指定された条件と対象が一致するかを判定する。 */
  private boolean matches(String seed, String text) {
    if (seed == null || text == null) return false;
    return text.toLowerCase(java.util.Locale.ROOT)
        .contains(seed.toLowerCase(java.util.Locale.ROOT));
  }
}
