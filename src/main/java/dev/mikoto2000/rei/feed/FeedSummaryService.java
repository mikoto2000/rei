package dev.mikoto2000.rei.feed;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class FeedSummaryService {

  private final FeedService feedService;
  private final FeedSummaryGenerator feedSummaryGenerator;
  private final FeedProperties feedProperties;

  public FeedSummaryService(FeedService feedService, FeedSummaryGenerator feedSummaryGenerator, FeedProperties feedProperties) {
    this.feedService = feedService;
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
        あなたは RSS/Atom フィードの見出しだけをもとに、新着記事の全体要約を行う。
        本文は保存されていないため、断定しすぎず、見出しと URL と公開日時から読み取れる範囲で要約する。
        次の形式で日本語で簡潔に返す:
        - 今日の主要トピック
        - 重要そうな記事
        - 後で読む価値が高いもの

        対象期間: %s から %s
        記事一覧:
        """.formatted(from, to));
    for (FeedBriefingItem item : items) {
      builder.append("- ")
          .append(item.publishedAt())
          .append(" | ")
          .append(item.feedName())
          .append(" | ")
          .append(item.title())
          .append(" | ")
          .append(item.url())
          .append('\n');
    }
    return builder.toString().trim();
  }

  private String buildItemPrompt(FeedBriefingItem item) {
    return """
        あなたは RSS/Atom フィードの見出し要約アシスタントです。
        本文は保存されていないため、次のメタデータだけを根拠に、分かること・分からないことを分けて日本語で要約してください。

        フィード: %s
        公開日時: %s
        タイトル: %s
        URL: %s
        """.formatted(item.feedName(), item.publishedAt(), item.title(), item.url());
  }
}
