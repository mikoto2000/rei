package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeedToolsTest {

  @Test
  void feedToolsDelegateToServices() {
    FeedService feedService = Mockito.mock(FeedService.class);
    FeedUpdateService updateService = Mockito.mock(FeedUpdateService.class);
    FeedSummaryService summaryService = Mockito.mock(FeedSummaryService.class);
    FeedTools tools = new FeedTools(feedService, updateService, summaryService);
    Feed created = new Feed(1L, "https://example.com/feed.xml", null, null, null, "Example Feed", true, null, null, null);
    when(feedService.list()).thenReturn(List.of());
    when(feedService.add("https://example.com/feed.xml", "Example Feed")).thenReturn(created);
    when(updateService.updateAll()).thenReturn(List.of(new FeedUpdateResult(1L, "Example Feed", 1, null)));
    when(summaryService.summarizeBriefing()).thenReturn("briefing summary");
    when(summaryService.summarizeItem(1L)).thenReturn("item summary");

    assertEquals(List.of(), tools.feedList());
    assertEquals(created, tools.feedAdd("https://example.com/feed.xml", "Example Feed"));
    assertEquals("削除: 1", tools.feedDelete(1L));
    assertTrue(tools.feedUpdate(null).contains("Example Feed"));
    assertEquals("briefing summary", tools.feedSummarizeBriefing());
    assertEquals("item summary", tools.feedSummarizeItem(1L));
    verify(feedService).list();
    verify(feedService).add("https://example.com/feed.xml", "Example Feed");
    verify(feedService).delete(1L);
  }
}
