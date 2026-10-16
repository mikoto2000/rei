package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.feed.FeedSummaryService;
import dev.mikoto2000.rei.feed.command.FeedCommand;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedReportsTest {
  @Test
  void listAndItemRangeUseShellFormattingAndExistingServices() {
    var service = mock(FeedService.class);
    when(service.list()).thenReturn(List.of());
    assertEquals("登録済みフィードはありません", FeedCommand.reportList(service));
    var from = OffsetDateTime.parse("2026-10-10T00:00:00Z");
    var to = OffsetDateTime.parse("2026-10-11T00:00:00Z");
    when(service.listBriefingItems(from, to, 10)).thenReturn(List.of());
    assertEquals("対象期間の記事はありません", FeedCommand.reportItems(service, from, to, 10));
    verify(service).listBriefingItems(from, to, 10);
  }

  @Test
  void itemSummarizeDoesNotNarrateOrDuplicateExecution() {
    var summary = mock(FeedSummaryService.class);
    when(summary.summarizeItem(42)).thenReturn("summary");
    assertEquals("summary", FeedCommand.reportItemSummary(summary, 42));
    verify(summary).summarizeItem(42);
  }
}
