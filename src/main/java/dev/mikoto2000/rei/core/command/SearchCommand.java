package dev.mikoto2000.rei.core.command;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import reactor.core.Disposable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Command(name = "search", description = "ベクトルストアと Web 検索の結果をまとめて回答します")
public class SearchCommand implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(SearchCommand.class);

  private final ChatClient chatClient;
  private final ModelHolderService currentModelHolder;
  private final SearchKnowledgeService searchKnowledgeService;
  private final CommandCancellationService cancellationService;

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
      cancellationService.begin(Thread.currentThread());
      SearchKnowledgeResult result = searchKnowledgeService.search(query, vectorTopK, webTopK, threshold, source);
      List<VectorDocumentSearchResult> vectorResults = result.vectorResults();
      WebSearchContext webContext = result.webContext();

      ChatClientRequestSpec requestSpec = chatClient.prompt(new Prompt(buildPrompt(query, vectorResults, webContext),
          OpenAiChatOptions.builder()
              .model(currentModelHolder.get())
              .build()));

      IO.println("=== answer ===");
      if (result.webSearchSkippedMessage() != null) {
        IO.println("[web search skipped] " + result.webSearchSkippedMessage());
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      Disposable disposable = requestSpec.stream()
          .content()
          .subscribe(
              System.out::print,
              error -> {
                errorRef.set(error);
                latch.countDown();
              },
              latch::countDown);
      cancellationService.register(disposable);

      boolean completed = latch.await(streamTimeoutMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        disposable.dispose();
        log.warn("Search response timed out after {} ms", streamTimeoutMillis());
        System.out.println();
        IO.println("[error] 回答の取得がタイムアウトしました");
        return;
      }
      System.out.println();
      Throwable error = errorRef.get();
      if (error != null) {
        log.warn("Search response failed", error);
        IO.println("[error] " + buildUserFacingMessage(error));
        return;
      }

      printSources(vectorResults, webContext);
    } catch (IOException e) {
      log.warn("Search knowledge retrieval failed", e);
      IO.println("[error] 検索結果の取得に失敗しました: " + safeMessage(e, "原因不明"));
    } catch (InterruptedException e) {
      if (cancellationService.consumeCancellationRequested()) {
        System.out.println();
        IO.println("[cancelled]");
        return;
      }
      Thread.currentThread().interrupt();
      log.warn("Search interrupted", e);
      IO.println("[error] 検索が中断されました");
    } finally {
      cancellationService.clear();
    }
  }

  private String buildUserFacingMessage(Throwable error) {
    Throwable root = rootCause(error);
    String message = safeMessage(root, "原因不明");
    return "回答の取得に失敗しました: " + message;
  }

  long streamTimeoutMillis() {
    return 1_800_000L;
  }

  private String safeMessage(Throwable error, String fallback) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }

  private Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private String buildPrompt(String query, List<VectorDocumentSearchResult> vectorResults, WebSearchContext webContext) {
    return """
        次の検索結果を材料に、日本語で回答してください。
        - まず結論を簡潔に示してください。
        - ベクトルストア由来の情報と Web 検索由来の情報を必要に応じて統合してください。
        - Web 一次情報を優先し、Web 補足情報は補強として扱ってください。
        - 情報が不足している点や不確かな点は断定しないでください。
        - Web 由来の記述には、可能な範囲で URL を文中または末尾に示してください。

        質問:
        %s

        ベクトルストア検索結果:
        %s

        Web 一次情報:
        %s

        Web 補足情報:
        %s
        """.formatted(
        query,
        formatVectorResults(vectorResults),
        formatWebResults(webContext.primaryResults()),
        formatWebResults(webContext.secondaryResults()));
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
          .append(" | snippet=").append(result.snippet())
          .append(" | content=").append(result.content())
          .append('\n');
    }
    return builder.toString().trim();
  }

  private void printSources(List<VectorDocumentSearchResult> vectorResults, WebSearchContext webContext) {
    Set<String> sources = new LinkedHashSet<>();
    for (VectorDocumentSearchResult result : vectorResults) {
      sources.add(result.source());
    }
    for (WebSearchPage result : webContext.allResults()) {
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
