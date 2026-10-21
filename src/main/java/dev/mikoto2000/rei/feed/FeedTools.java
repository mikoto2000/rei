package dev.mikoto2000.rei.feed;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** フィードをモデルから呼び出せるツールとして提供する。 */
@Component
@RequiredArgsConstructor
public class FeedTools {

  /** フィードサービス。 */
  private final FeedService feedService;

  /** フィード更新サービス。 */
  private final FeedUpdateService feedUpdateService;

  /** フィード要約サービス。 */
  private final FeedSummaryService feedSummaryService;

  /** フィードの購読と収集で用いるフィード一覧を返す。 */
  @Tool(name = "feedList", description = "登録済み RSS/Atom フィード一覧を返します。")
  List<Feed> feedList() {
    IO.println("RSS/Atom フィード一覧を取得するよ");
    return feedService.list();
  }

  /** フィードの購読と収集で用いるフィード追加を返す。 */
  @Tool(name = "feedAdd", description = "RSS/Atom フィードを登録します。")
  Feed feedAdd(String url, String displayName) {
    IO.println("RSS/Atom フィードを追加するよ");
    return feedService.add(url, displayName);
  }

  /** フィードの購読と収集で用いるフィード削除を返す。 */
  @Tool(name = "feedDelete", description = "登録済み RSS/Atom フィードを削除します。")
  String feedDelete(Long id) {
    IO.println("RSS/Atom フィードを削除するよ。id=" + id);
    feedService.delete(id);
    return "削除: " + id;
  }

  /** フィードの購読と収集で用いるフィード更新を返す。 */
  @Tool(name = "feedUpdate", description = "RSS/Atom フィードを更新します。id が null の場合は全件更新します。")
  String feedUpdate(Long id) {
    if (id == null) {
      IO.println("RSS/Atom フィードを全件更新するよ");
      return formatUpdateResults(feedUpdateService.updateAll());
    }
    IO.println("RSS/Atom フィードを更新するよ。id=" + id);
    return formatUpdateResults(List.of(feedUpdateService.update(id)));
  }

  /** フィードの購読と収集で用いるフィード要約日次案内を返す。 */
  @Tool(
      name = "feedSummarizeBriefing",
      description =
          "昨日 00:00 から現在までの新着記事をまとめて要約します。各記事について記事本文の取得を試み、取得できない記事は Feed 提供情報へフォールバックします。別途 fetchUrlContent を呼ぶ必要はありません。")
  String feedSummarizeBriefing() {
    IO.println("新着記事ブリーフィングを要約するよ");
    return feedSummaryService.summarizeBriefing();
  }

  /** フィードの購読と収集で用いるフィード要約項目を返す。 */
  @Tool(
      name = "feedSummarizeItem",
      description =
          "指定した Feed 記事を要約します。URL があれば記事本文の取得と要約を試み、取得できない場合は Feed 提供情報へフォールバックします。別途 fetchUrlContent を呼ぶ必要はありません。")
  FeedItemSummaryResult feedSummarizeItem(Long itemId) {
    IO.println("記事を要約するよ。itemId=" + itemId);
    return feedSummaryService.summarizeItemDetailed(itemId);
  }

  /** 内容を表示・参照しやすくするため、更新結果一覧を整形する。 */
  private String formatUpdateResults(List<FeedUpdateResult> results) {
    if (results.isEmpty()) {
      return "更新対象のフィードはありません";
    }
    StringBuilder builder = new StringBuilder();
    for (FeedUpdateResult result : results) {
      if (result.errorMessage() == null || result.errorMessage().isBlank()) {
        builder.append("- ").append(result.feedName()).append(" | +").append(result.addedItems());
      } else {
        builder
            .append("- ")
            .append(result.feedName())
            .append(" | error=")
            .append(result.errorMessage());
      }
      builder.append('\n');
    }
    return builder.toString().trim();
  }
}
