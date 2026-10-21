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

/** フィード伝えるの結果と状態の契約を検証するテスト。 */
class FeedReportsTest {
  /** Web APIによる操作で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
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

  /** Web APIによる操作で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void itemSummarizeDoesNotNarrateOrDuplicateExecution() {
    var summary = mock(FeedSummaryService.class);
    when(summary.summarizeItem(42)).thenReturn("summary");
    assertEquals("summary", FeedCommand.reportItemSummary(summary, 42));
    verify(summary).summarizeItem(42);
  }
}
