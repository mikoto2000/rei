package dev.mikoto2000.rei.search;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SearchTools {

  private final SearchKnowledgeService searchKnowledgeService;

  @Tool(name = "searchKnowledge", description = "必要なときだけ Web 検索とベクトル検索をまとめて実行します。最新情報、出典確認、ローカル文書確認が必要な場合に使います。")
  String searchKnowledge(String query, Integer vectorTopK, Integer webTopK, Double threshold, String source)
      throws IOException, InterruptedException {
    SearchKnowledgeResult result = searchKnowledgeService.search(query, vectorTopK, webTopK, threshold, source);
    return """
        質問:
        %s

        ベクトルストア検索結果:
        %s

        Web 一次情報:
        %s

        Web 補足情報:
        %s
        """.formatted(
        result.query(),
        formatVectorResults(result.vectorResults()),
        formatWebResults(result.webContext().primaryResults()),
        formatWebResults(result.webContext().secondaryResults()));
  }

  private String formatVectorResults(List<VectorDocumentSearchResult> results) {
    if (results.isEmpty()) {
      return "該当なし";
    }
    StringBuilder builder = new StringBuilder();
    for (VectorDocumentSearchResult result : results) {
      builder.append("- source=").append(result.source())
          .append(" | docId=").append(result.docId())
          .append(" | chunk=").append(result.chunkIndex())
          .append(" | score=").append(String.format(Locale.ROOT, "%.3f", result.score()))
          .append(" | snippet=").append(result.snippet())
          .append('\n');
    }
    return builder.toString().trim();
  }

  private String formatWebResults(List<WebSearchPage> results) {
    if (results.isEmpty()) {
      return "該当なし";
    }
    StringBuilder builder = new StringBuilder();
    for (WebSearchPage result : results) {
      builder.append("- title=").append(result.title())
          .append(" | url=").append(result.url())
          .append(" | publishedAt=").append(result.publishedAt())
          .append(" | content=").append(result.content())
          .append('\n');
    }
    return builder.toString().trim();
  }
}
