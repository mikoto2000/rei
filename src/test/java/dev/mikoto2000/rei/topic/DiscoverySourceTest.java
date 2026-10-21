package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.websearch.WebSearchAndReadItem;
import dev.mikoto2000.rei.websearch.WebSearchAndReadRequest;
import dev.mikoto2000.rei.websearch.WebSearchAndReadResponse;
import dev.mikoto2000.rei.websearch.WebSearchAndReadService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 利用先の検出取得元の結果と状態の契約を検証するテスト。 */
class DiscoverySourceTest {

  /** Web取得元について、利用先の検出項目一覧を返すことを確認する。 */
  @Test
  void webSourceReturnsDiscoveryItems() throws Exception {
    WebSearchAndReadService service = org.mockito.Mockito.mock(WebSearchAndReadService.class);
    when(service.searchAndRead(any(WebSearchAndReadRequest.class)))
        .thenReturn(
            new WebSearchAndReadResponse(
                "Dify MCP",
                List.of(
                    new WebSearchAndReadItem(
                        "Dify MCP authentication release",
                        "https://example.test/release",
                        "Dify MCP authentication changed",
                        "2026-09-01T00:00:00Z",
                        null,
                        null,
                        "success",
                        null,
                        null,
                        false))));

    List<DiscoveryItem> items =
        new WebDiscoverySource(service).discover(context("Dify MCP authentication"));

    assertEquals(1, items.size());
    assertEquals(TopicSource.WEB, items.getFirst().source());
    assertTrue(items.getFirst().relevance() >= 0.7);
  }

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void feedSourceReturnsOnlySeedRelatedItems() {
    FeedService service = org.mockito.Mockito.mock(FeedService.class);
    when(service.listBriefingItems(any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
        .thenReturn(
            List.of(
                new FeedBriefingItem(
                    1,
                    "Dify MCP authentication update",
                    "https://example.test/feed",
                    "related",
                    "content",
                    OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    "feed"),
                new FeedBriefingItem(
                    2,
                    "general news",
                    "https://example.test/news",
                    "unrelated",
                    "content",
                    OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    "feed")));

    List<DiscoveryItem> items =
        new FeedDiscoverySource(service).discover(context("Dify MCP authentication"));

    assertEquals(1, items.size());
    assertEquals(TopicSource.FEED, items.getFirst().source());
  }

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void githubSourceIsSafeWhenNoConnectorIsAvailable() {
    assertTrue(new GitHubDiscoverySource().discover(context("repo")).isEmpty());
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private DiscoveryContext context(String seed) {
    return new DiscoveryContext(List.of(seed), Instant.parse("2026-09-02T00:00:00Z"));
  }
}
