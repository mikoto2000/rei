package dev.mikoto2000.rei.feed;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FeedTools {

  private final FeedService feedService;
  private final FeedUpdateService feedUpdateService;
  private final FeedSummaryService feedSummaryService;

  @Tool(name = "feedList", description = "登録済み RSS/Atom フィード一覧を返します。")
  List<Feed> feedList() {
    IO.println("RSS/Atom フィード一覧を取得するよ");
    return feedService.list();
  }

  @Tool(name = "feedAdd", description = "RSS/Atom フィードを登録します。")
  Feed feedAdd(String url, String displayName) {
    IO.println(String.format("RSS/Atom フィードを追加するよ。url=%s、displayName=%s", url, displayName));
    return feedService.add(url, displayName);
  }

  @Tool(name = "feedDelete", description = "登録済み RSS/Atom フィードを削除します。")
  String feedDelete(Long id) {
    IO.println("RSS/Atom フィードを削除するよ。id=" + id);
    feedService.delete(id);
    return "削除: " + id;
  }

  @Tool(name = "feedUpdate", description = "RSS/Atom フィードを更新します。id が null の場合は全件更新します。")
  String feedUpdate(Long id) {
    if (id == null) {
      IO.println("RSS/Atom フィードを全件更新するよ");
      return formatUpdateResults(feedUpdateService.updateAll());
    }
    IO.println("RSS/Atom フィードを更新するよ。id=" + id);
    return formatUpdateResults(List.of(feedUpdateService.update(id)));
  }

  @Tool(name = "feedSummarizeBriefing", description = "昨日 00:00 から現在までの新着記事をまとめて要約します。")
  String feedSummarizeBriefing() {
    IO.println("新着記事ブリーフィングを要約するよ");
    return feedSummaryService.summarizeBriefing();
  }

  @Tool(name = "feedSummarizeItem", description = "指定した記事 ID の見出し情報だけを使って要約します。")
  String feedSummarizeItem(Long itemId) {
    IO.println("記事を要約するよ。itemId=" + itemId);
    return feedSummaryService.summarizeItem(itemId);
  }

  private String formatUpdateResults(List<FeedUpdateResult> results) {
    if (results.isEmpty()) {
      return "更新対象のフィードはありません";
    }
    StringBuilder builder = new StringBuilder();
    for (FeedUpdateResult result : results) {
      if (result.errorMessage() == null || result.errorMessage().isBlank()) {
        builder.append("- ").append(result.feedName()).append(" | +").append(result.addedItems());
      } else {
        builder.append("- ").append(result.feedName()).append(" | error=").append(result.errorMessage());
      }
      builder.append('\n');
    }
    return builder.toString().trim();
  }
}
