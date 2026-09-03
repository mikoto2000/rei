package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.feed.FeedBriefingItem;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.websearch.WebSearchAndReadItem;
import dev.mikoto2000.rei.websearch.WebSearchAndReadRequest;
import dev.mikoto2000.rei.websearch.WebSearchAndReadResponse;
import dev.mikoto2000.rei.websearch.WebSearchAndReadService;

class DiscoverySourceTest {

  @Test
  void webSourceReturnsDiscoveryItems() throws Exception {
    WebSearchAndReadService service = org.mockito.Mockito.mock(WebSearchAndReadService.class);
    when(service.searchAndRead(any(WebSearchAndReadRequest.class))).thenReturn(new WebSearchAndReadResponse("Dify MCP",
        List.of(new WebSearchAndReadItem("Dify MCP authentication release", "https://example.test/release",
            "Dify MCP authentication changed", "2026-09-01T00:00:00Z", null, null, "success", null, null, false))));

    List<DiscoveryItem> items = new WebDiscoverySource(service).discover(context("Dify MCP authentication"));

    assertEquals(1, items.size());
    assertEquals(TopicSource.WEB, items.getFirst().source());
    assertTrue(items.getFirst().relevance() >= 0.7);
  }

  @Test
  void feedSourceReturnsOnlySeedRelatedItems() {
    FeedService service = org.mockito.Mockito.mock(FeedService.class);
    when(service.listBriefingItems(any(OffsetDateTime.class), any(OffsetDateTime.class), anyInt()))
        .thenReturn(List.of(
            new FeedBriefingItem(1, "Dify MCP authentication update", "https://example.test/feed",
                "related", "content", OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC), "feed"),
            new FeedBriefingItem(2, "general news", "https://example.test/news",
                "unrelated", "content", OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC), "feed")));

    List<DiscoveryItem> items = new FeedDiscoverySource(service).discover(context("Dify MCP authentication"));

    assertEquals(1, items.size());
    assertEquals(TopicSource.FEED, items.getFirst().source());
  }

  @Test
  void githubSourceIsSafeWhenNoConnectorIsAvailable() {
    assertTrue(new GitHubDiscoverySource().discover(context("repo")).isEmpty());
  }

  private DiscoveryContext context(String seed) {
    return new DiscoveryContext(List.of(seed), Instant.parse("2026-09-02T00:00:00Z"));
  }
}
