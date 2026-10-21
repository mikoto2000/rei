package dev.mikoto2000.rei.interest;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 言語モデル関心話題に必要な情報を入力から取り出す。 */
@Component
public class LlmInterestTopicExtractor implements InterestTopicExtractor {
  /** 抽出タイムアウトの秒数。 */
  private static final long EXTRACT_TIMEOUT_SECONDS = 1200L;

  /** モデル提供元。 */
  private final LlmModelProvider modelProvider;

  /** モデル保持先サービス。 */
  private final ModelHolderService modelHolderService;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper objectMapper;

  /** 関心の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public LlmInterestTopicExtractor(
      ChatModel chatModel, ModelHolderService modelHolderService, JsonMapper objectMapper) {
    this(new FixedLlmModelProvider(chatModel), modelHolderService, objectMapper);
  }

  /** 指定された値や利用先を使って、LlmInterestTopicExtractorを初期化する。 */
  @Autowired
  public LlmInterestTopicExtractor(
      LlmModelProvider modelProvider,
      ModelHolderService modelHolderService,
      JsonMapper objectMapper) {
    this.modelProvider = modelProvider;
    this.modelHolderService = modelHolderService;
    this.objectMapper = objectMapper;
  }

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  @Override
  public List<InterestTopicCandidate> extract(List<ConversationSnippet> snippets, int maxTopics) {
    return extract(snippets, maxTopics, List.of());
  }

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
  @Override
  public List<InterestTopicCandidate> extract(
      List<ConversationSnippet> snippets, int maxTopics, List<String> pastQueries) {
    if (snippets.isEmpty()) {
      return List.of();
    }

    Prompt prompt =
        new Prompt(
            buildPrompt(snippets, maxTopics, pastQueries),
            modelProvider.chatOptions(LlmFeature.INTEREST_DISCOVERY, modelHolderService.get()));

    String response = callWithTimeout(prompt);
    return parse(response);
  }

  /** 関心の管理で用いる呼び出し付きタイムアウトを返す。 */
  private String callWithTimeout(Prompt prompt) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<String> future =
        executor.submit(
            () ->
                modelProvider
                    .chatModel(LlmFeature.INTEREST_DISCOVERY)
                    .call(prompt)
                    .getResult()
                    .getOutput()
                    .getText());
    try {
      return future.get(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException(
          "Interest topic extraction timed out after " + EXTRACT_TIMEOUT_SECONDS + " seconds", e);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interest topic extraction was interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Interest topic extraction failed", cause);
    } finally {
      executor.shutdownNow();
    }
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
  private String buildPrompt(
      List<ConversationSnippet> snippets, int maxTopics, List<String> pastQueries) {
    String conversation =
        snippets.stream()
            .map(snippet -> "- [%s] %s".formatted(snippet.createdAt(), snippet.text()))
            .collect(Collectors.joining("\n", "", ""));

    return """
        Extract candidate interest topics from user conversation history for later web search.
        Requirements:
        - Output must be a JSON array only.
        - Maximum %d items.
        - Each item must contain topic, reason, searchQuery, score.
        - score must be between 0.0 and 1.0.
        - Return [] when no candidate exists.
        %s
        Conversation history:
        %s
        """
        .formatted(maxTopics, buildPastQueriesSection(pastQueries), conversation);
  }

  /** 利用先に渡せるよう、Past検索条件一覧節を組み立てる。 */
  private String buildPastQueriesSection(List<String> pastQueries) {
    if (pastQueries.isEmpty()) {
      return "";
    }
    String queryList = pastQueries.stream().map(q -> "- " + q).collect(Collectors.joining("\n"));
    return """

        Previously used search queries. Avoid duplicates:
        %s
        """
        .formatted(queryList);
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  private List<InterestTopicCandidate> parse(String response) {
    try {
      return objectMapper.readValue(
          normalizeJsonArray(response), new TypeReference<List<InterestTopicCandidate>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse interest topic candidates", e);
    }
  }

  /** 同じ意味の指定を共通に扱えるよう、JSON変換Arrayの表記をそろえる。 */
  static String normalizeJsonArray(String response) {
    if (response == null) {
      return "[]";
    }
    String trimmed = response.trim();
    if (trimmed.isEmpty()) {
      return "[]";
    }

    if (trimmed.startsWith("```")) {
      int firstNewline = trimmed.indexOf('\n');
      if (firstNewline >= 0) {
        trimmed = trimmed.substring(firstNewline + 1).trim();
      } else {
        return "[]";
      }
      int closingFence = trimmed.lastIndexOf("```");
      if (closingFence >= 0) {
        trimmed = trimmed.substring(0, closingFence).trim();
      }
    }

    int arrayStart = trimmed.indexOf('[');
    int arrayEnd = trimmed.lastIndexOf(']');
    if (arrayStart >= 0 && arrayEnd >= arrayStart) {
      return trimmed.substring(arrayStart, arrayEnd + 1).trim();
    }
    return trimmed;
  }

  /** 固定言語モデルモデルを用途や設定に応じて提供する窓口。 */
  private static class FixedLlmModelProvider extends LlmModelProvider {
    /** 応答生成に使うモデル。 */
    private final ChatModel chatModel;

    /** 指定された値や利用先を使って、FixedLlmModelProviderを初期化する。 */
    FixedLlmModelProvider(ChatModel chatModel) {
      super(chatModel, new dev.mikoto2000.rei.llm.LlmProperties());
      this.chatModel = chatModel;
    }

    /** 関心の管理で用いる応答生成に使うモデルを返す。 */
    @Override
    public ChatModel chatModel(String feature) {
      return chatModel;
    }

    /** 関心の管理で用いるモデルを返す。 */
    @Override
    public String model(String feature, String defaultModel) {
      return defaultModel;
    }
  }
}
