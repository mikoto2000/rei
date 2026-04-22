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
    when(feedService.list()).thenReturn(List.of());
    when(updateService.updateAll()).thenReturn(List.of(new FeedUpdateResult(1L, "Example Feed", 1, null)));
    when(summaryService.summarizeBriefing()).thenReturn("briefing summary");
    when(summaryService.summarizeItem(1L)).thenReturn("item summary");

    assertEquals(List.of(), tools.feedList());
    assertTrue(tools.feedUpdate(null).contains("Example Feed"));
    assertEquals("briefing summary", tools.feedSummarizeBriefing());
    assertEquals("item summary", tools.feedSummarizeItem(1L));
    verify(feedService).list();
  }
}
