package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.annotation.Tool;

/** フィードツール一覧の結果と状態の契約を検証するテスト。 */
class FeedToolsTest {

  /** フィードの購読と収集で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void feedToolsDelegateToServices() {
    FeedService feedService = Mockito.mock(FeedService.class);
    FeedUpdateService updateService = Mockito.mock(FeedUpdateService.class);
    FeedSummaryService summaryService = Mockito.mock(FeedSummaryService.class);
    FeedTools tools = new FeedTools(feedService, updateService, summaryService);
    Feed created =
        new Feed(
            1L,
            "https://example.com/feed.xml",
            null,
            null,
            null,
            "Example Feed",
            true,
            null,
            null,
            null);
    when(feedService.list()).thenReturn(List.of());
    when(feedService.add("https://example.com/feed.xml", "Example Feed")).thenReturn(created);
    when(updateService.updateAll())
        .thenReturn(List.of(new FeedUpdateResult(1L, "Example Feed", 1, null)));
    when(summaryService.summarizeBriefing()).thenReturn("briefing summary");
    FeedItemSummaryResult itemSummary =
        new FeedItemSummaryResult("item summary", "article", "success");
    when(summaryService.summarizeItemDetailed(1L)).thenReturn(itemSummary);

    assertEquals(List.of(), tools.feedList());
    assertEquals(created, tools.feedAdd("https://example.com/feed.xml", "Example Feed"));
    assertEquals("削除: 1", tools.feedDelete(1L));
    assertTrue(tools.feedUpdate(null).contains("Example Feed"));
    assertEquals("briefing summary", tools.feedSummarizeBriefing());
    assertEquals(itemSummary, tools.feedSummarizeItem(1L));
    verify(feedService).list();
    verify(feedService).add("https://example.com/feed.xml", "Example Feed");
    verify(feedService).delete(1L);
  }

  /** フィードの購読と収集で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectedFeedUrlIsNotPrintedByTool() {
    FeedService feeds = Mockito.mock(FeedService.class);
    String url = "https://user:must-not-print@example.com/feed";
    when(feeds.add(url, null)).thenThrow(new IllegalArgumentException("invalid feed URL"));
    var output = new java.io.ByteArrayOutputStream();
    var original = System.out;
    try {
      System.setOut(new java.io.PrintStream(output));
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new FeedTools(feeds, null, null).feedAdd(url, null));
    } finally {
      System.setOut(original);
    }
    assertTrue(!output.toString().contains("must-not-print"));
  }

  /** フィードの購読と収集で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void summaryToolDescriptionsExplainArticleFetchAndFallback() throws Exception {
    Tool item =
        FeedTools.class
            .getDeclaredMethod("feedSummarizeItem", Long.class)
            .getAnnotation(Tool.class);
    Tool briefing =
        FeedTools.class.getDeclaredMethod("feedSummarizeBriefing").getAnnotation(Tool.class);

    assertTrue(item.description().contains("記事本文"));
    assertTrue(item.description().contains("フォールバック"));
    assertTrue(briefing.description().contains("記事本文"));
    assertTrue(briefing.description().contains("フォールバック"));
  }
}
