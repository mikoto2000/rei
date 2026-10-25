package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import reactor.core.Disposable;

/** ベクトルストアと Web 検索の結果をまとめて回答しますを行うための利用者コマンド。 */
@Component
@Command(name = "search", description = "ベクトルストアと Web 検索の結果をまとめて回答します")
public class SearchCommand implements Runnable {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(SearchCommand.class);

  /** モデルへ対話を依頼する窓口提供元。 */
  private final LlmChatClientProvider chatClientProvider;

  /** 現在モデル保持先。 */
  private final ModelHolderService currentModelHolder;

  /** モデル提供元。 */
  private final LlmModelProvider modelProvider;

  /** 検索知識サービス。 */
  private final SearchKnowledgeService searchKnowledgeService;

  /** キャンセルサービス。 */
  private final CommandCancellationService cancellationService;

  /** 出力。 */
  private java.util.function.Consumer<String> output = value -> System.out.print(value);

  /** 共通の実行基盤に関する改行付きの出力を処理する。 */
  private void println(String value) {
    output.accept(value + System.lineSeparator());
  }

  /** 共通の実行基盤に関するレポートを処理する。 */
  public void report(
      String query,
      int vectorTopK,
      int webTopK,
      Double threshold,
      String source,
      java.util.function.Consumer<String> output) {
    var command =
        new SearchCommand(
            chatClientProvider,
            currentModelHolder,
            modelProvider,
            searchKnowledgeService,
            cancellationService);
    command.queryParts = new String[] {query};
    command.vectorTopK = vectorTopK;
    command.webTopK = webTopK;
    command.threshold = threshold;
    command.source = source;
    command.output = java.util.Objects.requireNonNull(output);
    command.run();
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SearchCommand(
      ChatClient chatClient,
      ModelHolderService currentModelHolder,
      SearchKnowledgeService searchKnowledgeService,
      CommandCancellationService cancellationService) {
    this(
        new FixedLlmChatClientProvider(chatClient),
        currentModelHolder,
        new FixedLlmModelProvider(),
        searchKnowledgeService,
        cancellationService);
  }

  /** 指定された値や利用先を使って、SearchCommandを初期化する。 */
  @Autowired
  public SearchCommand(
      LlmChatClientProvider chatClientProvider,
      ModelHolderService currentModelHolder,
      LlmModelProvider modelProvider,
      SearchKnowledgeService searchKnowledgeService,
      CommandCancellationService cancellationService) {
    this.chatClientProvider = chatClientProvider;
    this.currentModelHolder = currentModelHolder;
    this.modelProvider = modelProvider;
    this.searchKnowledgeService = searchKnowledgeService;
    this.cancellationService = cancellationService;
  }

  /** ベクトル上位件数。 */
  @Option(names = "--vector-top-k", description = "ベクトル検索の返却件数")
  Integer vectorTopK = 3;

  /** Web上位件数。 */
  @Option(names = "--web-top-k", description = "Web 検索の返却件数")
  Integer webTopK = 5;

  /** 判定の境界値。 */
  @Option(names = "--threshold", description = "ベクトル検索の類似度しきい値")
  Double threshold;

  /** 取得元。 */
  @Option(names = "--source", description = "ベクトル検索を source で絞り込みます")
  String source;

  /** 検索条件部分一覧。 */
  @Parameters(arity = "1..*", paramLabel = "QUERY", description = "検索クエリ")
  String[] queryParts;

  /** ベクトルストアと Web 検索の結果をまとめて回答しますを行う。 */
  @Override
  public void run() {
    long startedAtNanos = System.nanoTime();
    String query = String.join(" ", queryParts);
    try {
      cancellationService.begin(Thread.currentThread());
      SearchKnowledgeResult result =
          searchKnowledgeService.search(query, vectorTopK, webTopK, threshold, source);
      List<VectorDocumentSearchResult> vectorResults = result.vectorResults();
      WebSearchContext webContext = result.webContext();

      ChatClientRequestSpec requestSpec =
          chatClientProvider
              .chatClient(LlmFeature.SEARCH)
              .prompt(
                  new Prompt(
                      buildPrompt(query, vectorResults, webContext),
                      modelProvider.chatOptions(LlmFeature.SEARCH, currentModelHolder.get())))
              .advisors(
                  advisor ->
                      advisor.param(ChatMemory.CONVERSATION_ID, ConversationIds.tool("search")));

      if (result.webSearchSkippedMessage() != null) {
        println("[web search skipped] " + result.webSearchSkippedMessage());
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Throwable> errorRef = new AtomicReference<>();
      AtomicBoolean headerPrinted = new AtomicBoolean(false);
      Disposable disposable =
          requestSpec.stream()
              .content()
              .subscribe(
                  chunk -> {
                    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
                    if (headerPrinted.compareAndSet(false, true)) {
                      println(answerHeader(startedAtNanos));
                    }
                    output.accept(chunk);
                  },
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
        println("");
        println("[error] 回答の取得がタイムアウトしました");
        return;
      }
      println("");
      Throwable error = errorRef.get();
      if (error != null) {
        log.warn("Search response failed", error);
        println("[error] " + buildUserFacingMessage(error));
        return;
      }

      printSources(vectorResults, webContext);
    } catch (IOException e) {
      log.warn("Search knowledge retrieval failed", e);
      println("[error] 検索結果の取得に失敗しました: " + safeMessage(e, "原因不明"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (cancellationService.consumeCancellationRequested()) {
        println("");
        println("[cancelled] ユーザーの停止要求により検索を中止しました。");
        return;
      }
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      log.warn("Search interrupted", e);
      println("[error] 検索が中断されました: " + dev.mikoto2000.rei.event.FailureMessages.describe(e));
    } finally {
      cancellationService.clear();
    }
  }

  /** 利用先に渡せるよう、利用者Facingメッセージを組み立てる。 */
  private String buildUserFacingMessage(Throwable error) {
    Throwable root = rootCause(error);
    String message = safeMessage(root, "原因不明");
    return "回答の取得に失敗しました: " + message;
  }

  /** 共通の実行基盤で用いる逐次データタイムアウトのミリ秒数を返す。 */
  long streamTimeoutMillis() {
    return 1_800_000L;
  }

  /** 共通の実行基盤で用いる安全性メッセージを返す。 */
  private String safeMessage(Throwable error, String fallback) {
    return error == null ? fallback : dev.mikoto2000.rei.event.FailureMessages.describe(error);
  }

  /** 共通の実行基盤で用いる作業ルート原因を返す。 */
  private Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
  private String buildPrompt(
      String query, List<VectorDocumentSearchResult> vectorResults, WebSearchContext webContext) {
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
        """
        .formatted(
            query,
            formatVectorResults(vectorResults),
            formatWebResults(webContext.primaryResults()),
            formatWebResults(webContext.secondaryResults()));
  }

  /** 内容を表示・参照しやすくするため、ベクトル結果一覧を整形する。 */
  private String formatVectorResults(List<VectorDocumentSearchResult> results) {
    if (results.isEmpty()) {
      return "該当なし";
    }
    StringBuilder builder = new StringBuilder();
    for (VectorDocumentSearchResult result : results) {
      builder
          .append("- source=")
          .append(result.source())
          .append(" | docId=")
          .append(result.docId())
          .append(" | chunk=")
          .append(result.chunkIndex())
          .append(" | score=")
          .append(String.format(Locale.ROOT, "%.3f", result.score()))
          .append(" | snippet=")
          .append(result.snippet())
          .append('\n');
    }
    return builder.toString().trim();
  }

  /** 内容を表示・参照しやすくするため、Web結果一覧を整形する。 */
  private String formatWebResults(List<WebSearchPage> results) {
    if (results.isEmpty()) {
      return "該当なし";
    }
    StringBuilder builder = new StringBuilder();
    for (WebSearchPage result : results) {
      builder
          .append("- title=")
          .append(result.title())
          .append(" | url=")
          .append(result.url())
          .append(" | publishedAt=")
          .append(result.publishedAt())
          .append(" | snippet=")
          .append(result.snippet())
          .append(" | content=")
          .append(result.content())
          .append('\n');
    }
    return builder.toString().trim();
  }

  /** 利用者に結果を伝えるため、取得元一覧を出力する。 */
  private void printSources(
      List<VectorDocumentSearchResult> vectorResults, WebSearchContext webContext) {
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
    println("=== sources ===");
    for (String value : sources) {
      println("- " + value);
    }
  }

  /** 共通の実行基盤で用いる回答ヘッダーを返す。 */
  String answerHeader(long startedAtNanos) {
    double elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0d;
    return "=== answer(" + String.format(Locale.ROOT, "%.1f", elapsedSeconds) + " s) ===";
  }
}
