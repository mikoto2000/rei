package dev.mikoto2000.rei.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/** 用途に応じたモデル利用に必要な代替手段応答生成に使うモデルを扱う。 */
class FallbackChatModel implements ChatModel {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

  /** 機能。 */
  private final String feature;

  /** 主要な。 */
  private final ChatModel primary;

  /** 代替手段。 */
  private final ChatModel fallback;

  /** 主要なモデル。 */
  private final String primaryModel;

  /** 指定された値や利用先を使って、FallbackChatModelを初期化する。 */
  FallbackChatModel(String feature, ChatModel primary, ChatModel fallback, String primaryModel) {
    this.feature = feature;
    this.primary = primary;
    this.fallback = fallback;
    this.primaryModel = primaryModel;
  }

  /** 用途に応じたモデル利用の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public ChatResponse call(Prompt prompt) {
    dev.mikoto2000.rei.core.chat.RunCancellation.checkActive(prompt);
    try {
      return primary.call(prompt);
    } catch (RuntimeException e) {
      dev.mikoto2000.rei.core.chat.RunCancellation.checkActive(prompt);
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(e);
      logFeatureFailure("call", e);
      return fallback.call(fallbackPrompt(prompt));
    }
  }

  /** 用途に応じたモデル利用で用いる逐次データを返す。 */
  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(
            () -> {
              dev.mikoto2000.rei.core.chat.RunCancellation.checkActive(prompt);
              return primary.stream(prompt);
            })
        .onErrorResume(
            error -> {
              dev.mikoto2000.rei.core.chat.RunCancellation.checkActive(prompt);
              dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
              logFeatureFailure("stream", error);
              return fallback.stream(fallbackPrompt(prompt));
            });
  }

  /** 用途に応じたモデル利用で参照する選択可能な設定を返す。 */
  @Override
  public ChatOptions getOptions() {
    return primary.getOptions();
  }

  /** 用途に応じたモデル利用で用いる代替手段プロンプトを返す。 */
  private Prompt fallbackPrompt(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    if (options == null || primaryModel == null || primaryModel.isBlank()) {
      return prompt;
    }
    if (!primaryModel.equals(options.getModel())) {
      return prompt;
    }
    ChatOptions fallbackOptions = fallbackOptionsWithoutPrimaryModel(options);
    return Prompt.builder().messages(prompt.getInstructions()).chatOptions(fallbackOptions).build();
  }

  /** 用途に応じたモデル利用で用いる代替手段選択可能な設定なし主要なモデルを返す。 */
  private ChatOptions fallbackOptionsWithoutPrimaryModel(ChatOptions options) {
    String fallbackModel = fallback.getOptions() == null ? null : fallback.getOptions().getModel();
    if (options instanceof OpenAiChatOptions openAiOptions && fallbackModel != null) {
      return openAiOptions.mutate().model(fallbackModel).build();
    }
    if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions toolOptions) {
      return org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
          .combineWith(toolOptions.mutate())
          .model(fallbackModel)
          .build();
    }
    return ChatOptions.builder()
        .model(fallbackModel)
        .frequencyPenalty(options.getFrequencyPenalty())
        .maxTokens(options.getMaxTokens())
        .presencePenalty(options.getPresencePenalty())
        .stopSequences(options.getStopSequences())
        .temperature(options.getTemperature())
        .topK(options.getTopK())
        .topP(options.getTopP())
        .build();
  }

  /** 用途に応じたモデル利用に関するログ機能失敗情報を処理する。 */
  private void logFeatureFailure(String operation, Throwable error) {
    WebClientResponseException responseException = findResponseException(error);
    if (responseException == null) {
      log.warn(
          "Feature LLM {} failed. Falling back to default LLM: feature={}",
          operation,
          feature,
          error);
      return;
    }
    log.warn(
        "Feature LLM {} failed. Falling back to default LLM: feature={}, status={}, responseBody={}",
        operation,
        feature,
        responseException.getStatusCode(),
        responseException.getResponseBodyAsString(),
        error);
  }

  /** 対象を特定できるよう、応答例外を探す。 */
  private WebClientResponseException findResponseException(Throwable error) {
    if (error == null) {
      return null;
    }
    if (error instanceof WebClientResponseException responseException) {
      return responseException;
    }
    WebClientResponseException cause = findResponseException(error.getCause());
    if (cause != null) {
      return cause;
    }
    for (Throwable suppressed : error.getSuppressed()) {
      WebClientResponseException suppressedResponse = findResponseException(suppressed);
      if (suppressedResponse != null) {
        return suppressedResponse;
      }
    }
    return null;
  }
}
