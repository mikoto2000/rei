package dev.mikoto2000.rei.feed;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.websearch.WebSearchPage;

@Service
public class FeedSummaryService {

  private final FeedService feedService;
  private final Function<FeedBriefingItem, WebSearchPage> feedArticlePageFetcher;
  private final FeedSummaryGenerator feedSummaryGenerator;
  private final FeedProperties feedProperties;

  public FeedSummaryService(FeedService feedService, Function<FeedBriefingItem, WebSearchPage> feedArticlePageFetcher,
      FeedSummaryGenerator feedSummaryGenerator, FeedProperties feedProperties) {
    this.feedService = feedService;
    this.feedArticlePageFetcher = feedArticlePageFetcher;
    this.feedSummaryGenerator = feedSummaryGenerator;
    this.feedProperties = feedProperties;
  }

  public String summarizeBriefing() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    return summarizeBriefing(
        today.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  public String summarizeBriefing(OffsetDateTime from, OffsetDateTime to) {
    List<FeedBriefingItem> items = feedService.listBriefingItems(from, to, feedProperties.briefingMaxItems());
    if (items.isEmpty()) {
      return "昨日 00:00 以降の新着記事はありませんでした";
    }
    try {
      return feedSummaryGenerator.generate(buildBriefingPrompt(from, to, items));
    } catch (RuntimeException e) {
      return "新着記事の要約生成に失敗しました。見出し一覧を確認してください。";
    }
  }

  public String summarizeItem(long itemId) {
    FeedBriefingItem item = feedService.findBriefingItem(itemId);
    try {
      return feedSummaryGenerator.generate(buildItemPrompt(item));
    } catch (RuntimeException e) {
      return "記事要約の生成に失敗しました。タイトルと URL を確認してください。";
    }
  }

  private String buildBriefingPrompt(OffsetDateTime from, OffsetDateTime to, List<FeedBriefingItem> items) {
    StringBuilder builder = new StringBuilder();
    builder.append("""
        あなたは RSS/Atom フィードから取得した記事ページ本文をもとに、新着記事の全体要約を行う。
        本文取得に失敗した記事はメタデータだけが含まれることがある。その場合は断定しすぎない。
        次の形式で日本語で簡潔に返す:
        - 今日の主要トピック
        - 重要そうな記事
        - 後で読む価値が高いもの

        対象期間: %s から %s
        記事一覧:
        """.formatted(from, to));
    for (FeedBriefingItem item : items) {
      WebSearchPage page = fetchPage(item);
      builder.append("- ")
          .append(item.publishedAt())
          .append(" | ")
          .append(item.feedName())
          .append(" | ")
          .append(item.title())
          .append(" | ")
          .append(item.url())
          .append(" | content=")
          .append(page.content())
          .append('\n');
    }
    return builder.toString().trim();
  }

  private String buildItemPrompt(FeedBriefingItem item) {
    WebSearchPage page = fetchPage(item);
    return """
        あなたは RSS/Atom フィードの記事要約アシスタントです。
        次の本文とメタデータを根拠に、重要点を日本語で簡潔に要約してください。
        本文取得に失敗して本文が短い場合は、その旨を踏まえて断定しすぎないこと。

        フィード: %s
        公開日時: %s
        タイトル: %s
        URL: %s
        本文:
        %s
        """.formatted(item.feedName(), item.publishedAt(), item.title(), item.url(), page.content());
  }

  private WebSearchPage fetchPage(FeedBriefingItem item) {
    try {
      WebSearchPage page = feedArticlePageFetcher.apply(item);
      if (page == null) {
        return fallbackPage(item);
      }
      String content = page.content();
      if (content == null || content.isBlank()) {
        return fallbackPage(item);
      }
      return page;
    } catch (Exception e) {
      return fallbackPage(item);
    }
  }

  private WebSearchPage fallbackPage(FeedBriefingItem item) {
    return new WebSearchPage(
        item.title(),
        item.url(),
        "",
        item.publishedAt() == null ? null : item.publishedAt().toString(),
        "title=" + item.title() + " url=" + item.url());
  }
}
