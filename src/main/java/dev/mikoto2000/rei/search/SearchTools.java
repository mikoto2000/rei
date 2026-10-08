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

  @Tool(name = "searchKnowledge", description = """
      Search the agent's indexed knowledge base and supplement it with public-web sources.
      Use this hybrid workflow when registered internal knowledge is relevant to the question.
      For public-web-only research, prefer webSearchAndRead.
      """)
  String searchKnowledge(String query, Integer vectorTopK, Integer webTopK, Double threshold, String source,
      @org.springframework.ai.tool.annotation.ToolParam(required = false, description = "Revalidate cached web sources") Boolean forceRefresh,
      org.springframework.ai.chat.model.ToolContext context) throws IOException, InterruptedException {
    try (var scope = dev.mikoto2000.rei.http.FetchScope.enter(context);
        var refresh = dev.mikoto2000.rei.http.FetchScope.withForceRefresh(Boolean.TRUE.equals(forceRefresh))) {
      return searchKnowledge(query, vectorTopK, webTopK, threshold, source);
    }
  }
  String searchKnowledge(String query, Integer vectorTopK, Integer webTopK, Double threshold, String source,
      org.springframework.ai.chat.model.ToolContext context) throws IOException, InterruptedException {
    return searchKnowledge(query, vectorTopK, webTopK, threshold, source, null, context);
  }
  String searchKnowledge(String query, Integer vectorTopK, Integer webTopK, Double threshold, String source)
      throws IOException, InterruptedException {
    IO.println(String.format("知識検索を実行するよ。vectorTopK=%s、webTopK=%s", vectorTopK, webTopK));
    SearchKnowledgeResult result = searchKnowledgeService.search(query, vectorTopK, webTopK, threshold, source);
    return """
        質問:
        %s

        ベクトルストア検索結果:
        %s

        Web 一次情報 / Web 補足情報（検索順位順）:
        %s
        """.formatted(
        result.query(),
        formatVectorResults(result.vectorResults()),
        formatWebResults(result.webContext()));
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

  private String formatWebResults(dev.mikoto2000.rei.websearch.WebSearchContext context) {
    List<WebSearchPage> results = context.allResults();
    if (results.isEmpty()) {
      return "該当なし";
    }
    StringBuilder builder = new StringBuilder();
    for (WebSearchPage result : results) {
      builder.append("- title=").append(result.title())
          .append(" | sourceType=").append(context.primaryResults().contains(result) ? "primary" : "secondary")
          .append(" | fetchStatus=").append(result.fetchStatus()).append(" | errorType=").append(result.errorType())
          .append(" | url=").append(result.url())
          .append(" | publishedAt=").append(result.publishedAt())
          .append(" | content=").append(result.content())
          .append('\n');
      for (var alias : result.aliases()) if (!alias.url().equals(result.url()))
        builder.append("  - citation=").append(alias.url()).append(" | evidenceType=").append(alias.evidenceType()).append('\n');
    }
    return builder.toString().trim();
  }
}
