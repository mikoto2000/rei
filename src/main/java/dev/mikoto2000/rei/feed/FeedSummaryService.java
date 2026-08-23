package dev.mikoto2000.rei.feed;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.websearch.WebSearchPage;

@Service
public class FeedSummaryService {

  private static final Logger log = LoggerFactory.getLogger(FeedSummaryService.class);
  private static final int MAX_FEED_CONTENT_LENGTH = 2000;

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
    return summarizeItemDetailed(itemId).summary();
  }

  public FeedItemSummaryResult summarizeItemDetailed(long itemId) {
    FeedBriefingItem item = feedService.findBriefingItem(itemId);
    ResolvedContent resolved = resolveContent(item);
    if (resolved.content().isBlank() && isBlank(item.title())) {
      return new FeedItemSummaryResult("要約可能な記事情報がありません。", "feed", resolved.fetchStatus());
    }
    try {
      String summary = feedSummaryGenerator.generate(buildItemPrompt(item, resolved));
      log.debug("feed item summarized: itemId={}, articleUrl={}, articleFetchStatus={}, summarySource={}, truncated={}",
          item.id(), item.url(), resolved.fetchStatus(), resolved.source(), resolved.truncated());
      return new FeedItemSummaryResult(summary, resolved.source(), resolved.fetchStatus());
    } catch (RuntimeException e) {
      return new FeedItemSummaryResult("記事要約の生成に失敗しました。タイトルと URL を確認してください。",
          resolved.source(), resolved.fetchStatus());
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
        「重要そうな記事」と「後で読む価値が高いもの」では、各記事の紹介文の直後に対応する URL を必ず記載すること。
        例:
        - 重要そうな記事
          - LiteLLM Proxy の管理 UI と DB 接続の注意点をまとめた記事。URL: https://example.com/article
        - 後で読む価値が高いもの
          - 設定の詰まりどころが具体的で再現時に役立つ。URL: https://example.com/article

        対象期間: %s から %s
        記事一覧:
        """.formatted(from, to));
    Map<String, ArticleFetch> fetchCache = new LinkedHashMap<>();
    for (FeedBriefingItem item : items) {
      ResolvedContent resolved = resolveContent(item, fetchCache);
      builder.append("- ")
          .append(item.publishedAt())
          .append(" | ")
          .append(item.feedName())
          .append(" | ")
          .append(item.title())
          .append(" | ")
          .append(item.url())
          .append(" | summarySource=")
          .append(resolved.source())
          .append(" | articleFetchStatus=")
          .append(resolved.fetchStatus())
          .append(" | content=")
          .append(resolved.content())
          .append('\n');
    }
    log.debug("feed briefing content resolved: items={}, uniqueArticleUrls={}", items.size(), fetchCache.size());
    return builder.toString().trim();
  }

  private String buildItemPrompt(FeedBriefingItem item, ResolvedContent resolved) {
    if ("article".equals(resolved.source())) {
      return """
        あなたは RSS/Atom フィードの記事要約アシスタントです。
        次の記事本文を主な根拠に、重要点を日本語で簡潔に要約してください。
        与えられた情報だけを使い、不足情報を推測で補完しないでください。

        フィード: %s
        公開日時: %s
        タイトル: %s
        URL: %s
        記事本文:
        %s
        """.formatted(item.feedName(), item.publishedAt(), item.title(), item.url(), resolved.content());
    }
    return """
        あなたは RSS/Atom フィードの記事要約アシスタントです。
        記事本文は取得できませんでした。以下の Feed 提供情報だけを使って要約してください。
        不足情報を推測で補完せず、本文を読んだかのような詳細を生成しないでください。

        フィード: %s
        公開日時: %s
        タイトル: %s
        URL: %s
        Feed content:
        %s
        """.formatted(item.feedName(), item.publishedAt(), item.title(), item.url(), resolved.content());
  }

  private ResolvedContent resolveContent(FeedBriefingItem item) {
    return resolveContent(item, new LinkedHashMap<>());
  }

  private ResolvedContent resolveContent(FeedBriefingItem item, Map<String, ArticleFetch> fetchCache) {
    if (isBlank(item.url())) {
      return feedContent(item, "not_requested");
    }
    ArticleFetch fetched = fetchCache.computeIfAbsent(item.url(), ignored -> fetchArticle(item));
    if (fetched.success()) {
      return new ResolvedContent(fetched.content(), "article", "success", fetched.truncated());
    }
    return feedContent(item, "failed");
  }

  private ArticleFetch fetchArticle(FeedBriefingItem item) {
    try {
      WebSearchPage page = feedArticlePageFetcher.apply(item);
      if (page != null && !isBlank(page.content())) {
        return new ArticleFetch(page.content().trim(), true, page.truncated());
      }
      log.debug("feed article fetch produced empty content: itemId={}, articleUrl={}", item.id(), item.url());
    } catch (Exception e) {
      log.debug("feed article fetch failed: itemId={}, articleUrl={}, reason={}", item.id(), item.url(), e.toString());
    }
    return new ArticleFetch("", false, false);
  }

  private ResolvedContent feedContent(FeedBriefingItem item, String fetchStatus) {
    String content = firstNonBlank(item.content(), item.description(), item.title());
    String normalized = content == null ? "" : Jsoup.parse(content).text().replaceAll("\\s+", " ").trim();
    boolean truncated = normalized.length() > MAX_FEED_CONTENT_LENGTH;
    if (truncated) {
      int end = normalized.offsetByCodePoints(0, Math.min(MAX_FEED_CONTENT_LENGTH,
          normalized.codePointCount(0, normalized.length())));
      normalized = normalized.substring(0, end);
    }
    return new ResolvedContent(normalized, "feed", fetchStatus, truncated);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (!isBlank(value)) return value;
    }
    return "";
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record ResolvedContent(String content, String source, String fetchStatus, boolean truncated) {
  }

  private record ArticleFetch(String content, boolean success, boolean truncated) {
  }
}
