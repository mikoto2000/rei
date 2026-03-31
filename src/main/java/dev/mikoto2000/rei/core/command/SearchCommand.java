package dev.mikoto2000.rei.core.command;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import dev.mikoto2000.rei.websearch.WebSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@RequiredArgsConstructor
@Command(name = "search", description = "ベクトルストアと Web 検索の結果をまとめて回答します")
public class SearchCommand implements Runnable {

  private final ChatClient chatClient;
  private final ModelHolderService currentModelHolder;
  private final VectorDocumentService vectorDocumentService;
  private final WebSearchService webSearchService;

  @Option(names = "--vector-top-k", description = "ベクトル検索の返却件数")
  Integer vectorTopK = 3;

  @Option(names = "--web-top-k", description = "Web 検索の返却件数")
  Integer webTopK = 5;

  @Option(names = "--threshold", description = "ベクトル検索の類似度しきい値")
  Double threshold;

  @Option(names = "--source", description = "ベクトル検索を source で絞り込みます")
  String source;

  @Parameters(arity = "1..*", paramLabel = "QUERY", description = "検索クエリ")
  String[] queryParts;

  @Override
  public void run() {
    String query = String.join(" ", queryParts);
    try {
      List<VectorDocumentSearchResult> vectorResults = vectorDocumentService.search(query, vectorTopK, threshold, source);
      List<WebSearchResult> webResults = webSearchService.search(query, webTopK);

      ChatClientRequestSpec requestSpec = chatClient.prompt(new Prompt(buildPrompt(query, vectorResults, webResults),
          OpenAiChatOptions.builder()
              .model(currentModelHolder.get())
              .build()));
      ChatClientResponse chatClientResponse = requestSpec.call().chatClientResponse();

      String thinking = chatClientResponse.chatResponse().getResult().getMetadata().get("thinking");
      if (thinking != null && !thinking.isBlank()) {
        IO.println("=== thinking ===");
        IO.println(thinking);
      }

      IO.println("=== answer ===");
      IO.println(chatClientResponse.chatResponse().getResult().getOutput().getText());

      printSources(vectorResults, webResults);
    } catch (IOException e) {
      throw new RuntimeException("検索結果の取得に失敗しました", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("検索が中断されました", e);
    }
  }

  private String buildPrompt(String query, List<VectorDocumentSearchResult> vectorResults, List<WebSearchResult> webResults) {
    return """
        次の検索結果を材料に、日本語で回答してください。
        - まず結論を簡潔に示してください。
        - ベクトルストア由来の情報と Web 検索由来の情報を必要に応じて統合してください。
        - 情報が不足している点や不確かな点は断定しないでください。
        - Web 由来の記述には、可能な範囲で URL を文中または末尾に示してください。

        質問:
        %s

        ベクトルストア検索結果:
        %s

        Web 検索結果:
        %s
        """.formatted(query, formatVectorResults(vectorResults), formatWebResults(webResults));
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

  private String formatWebResults(List<WebSearchResult> results) {
    if (results.isEmpty()) {
      return "該当なし";
    }
    StringBuilder builder = new StringBuilder();
    for (WebSearchResult result : results) {
      builder.append("- title=").append(result.title())
          .append(" | url=").append(result.url())
          .append(" | publishedAt=").append(result.publishedAt())
          .append(" | snippet=").append(result.snippet())
          .append('\n');
    }
    return builder.toString().trim();
  }

  private void printSources(List<VectorDocumentSearchResult> vectorResults, List<WebSearchResult> webResults) {
    Set<String> sources = new LinkedHashSet<>();
    for (VectorDocumentSearchResult result : vectorResults) {
      sources.add(result.source());
    }
    for (WebSearchResult result : webResults) {
      sources.add(result.url());
    }
    if (sources.isEmpty()) {
      return;
    }
    IO.println("=== sources ===");
    for (String value : sources) {
      IO.println("- " + value);
    }
  }
}
