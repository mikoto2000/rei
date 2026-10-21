package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.util.UUID;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 用途に応じたモデル利用に必要なエージェントイベント応答生成に使うモデルを扱う。
 *
 * <p>Publishes lifecycle events for text LLM invocations except background activity detection.
 */
final class AgentEventChatModel implements ChatModel, dev.mikoto2000.rei.timing.TimingAwareModel {
  /** 用途に応じたモデル利用で用いる時間計測を返す。 */
  public dev.mikoto2000.rei.timing.TimingExecution timing() {
    return timing;
  }

  /** 機能。 */
  private final String feature;

  /** 委譲先。 */
  private final ChatModel delegate;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 時間計測。 */
  private final dev.mikoto2000.rei.timing.TimingExecution timing;

  /** 用途に応じたモデル利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  AgentEventChatModel(
      String feature,
      ChatModel delegate,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(feature, delegate, eventFactory, eventPublisher, null);
  }

  /** 指定された値や利用先を使って、AgentEventChatModelを初期化する。 */
  AgentEventChatModel(
      String feature,
      ChatModel delegate,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,
      dev.mikoto2000.rei.timing.TimingExecution timing) {
    this.timing = timing;
    this.feature = feature;
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 用途に応じたモデル利用の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public ChatResponse call(Prompt prompt) {
    // Activity polling must not produce Shell or Web API notifications.
    if (LlmFeature.ACTIVITY.equals(feature)) return delegate.call(prompt);
    String requestId = UUID.randomUUID().toString();
    var measured = measurement(prompt, requestId);
    long startedAtNanos = System.nanoTime();
    publisher(prompt)
        .publish(eventFactory.llmRequestStarted(null, requestId, observedFeature(prompt)));
    try {
      ChatResponse response = delegate.call(prompt);
      publisher(prompt)
          .publish(
              eventFactory.llmResponseCompleted(null, requestId, elapsedMillis(startedAtNanos)));
      observeUsage(measured, response);
      if (measured != null)
        measured.finish(dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS);
      return response;
    } catch (RuntimeException e) {
      if (measured != null) measured.finish(dev.mikoto2000.rei.timing.TimingExecution.status(e));
      publisher(prompt)
          .publish(
              eventFactory.llmRequestFailed(null, requestId, elapsedMillis(startedAtNanos), e));
      throw e;
    }
  }

  /** 用途に応じたモデル利用で用いる逐次データを返す。 */
  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    if (LlmFeature.ACTIVITY.equals(feature)) return Flux.defer(() -> delegate.stream(prompt));
    return Flux.deferContextual(
        context -> {
          String requestId = UUID.randomUUID().toString();
          dev.mikoto2000.rei.timing.TimingAttempt trace =
              context.getOrDefault(dev.mikoto2000.rei.timing.TimingAttempt.KEY, null);
          var measured = measurement(prompt, requestId, trace);
          long startedAtNanos = System.nanoTime();
          java.util.concurrent.atomic.AtomicBoolean firstTokenPublished =
              new java.util.concurrent.atomic.AtomicBoolean();
          publisher(prompt)
              .publish(eventFactory.llmRequestStarted(null, requestId, observedFeature(prompt)));
          return Flux.defer(() -> delegate.stream(prompt))
              .doOnNext(
                  response -> {
                    observeChunk(prompt, measured, response);
                    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
                    if (firstTokenPublished.compareAndSet(false, true)) {
                      publisher(prompt)
                          .publish(
                              eventFactory.llmResponseFirstToken(
                                  null, requestId, elapsedMillis(startedAtNanos)));
                    }
                  })
              .doOnComplete(
                  () -> {
                    if (measured != null)
                      measured.finish(dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS);
                    publisher(prompt)
                        .publish(
                            eventFactory.llmResponseCompleted(
                                null, requestId, elapsedMillis(startedAtNanos)));
                  })
              .doOnError(
                  error -> {
                    if (measured != null)
                      measured.finish(dev.mikoto2000.rei.timing.TimingExecution.status(error));
                    publisher(prompt)
                        .publish(
                            eventFactory.llmRequestFailed(
                                null, requestId, elapsedMillis(startedAtNanos), error));
                  })
              .doOnCancel(
                  () -> {
                    if (measured != null)
                      measured.finish(
                          cancelled(prompt)
                              ? dev.mikoto2000.rei.timing.TimingRecorder.Status.CANCELLED
                              : dev.mikoto2000.rei.timing.TimingRecorder.Status.DISCONNECTED);
                  });
        });
  }

  /** 用途に応じたモデル利用で参照する選択可能な設定を返す。 */
  @Override
  public ChatOptions getOptions() {
    return delegate.getOptions();
  }

  /** 用途に応じたモデル利用で用いる所有者を返す。 */
  private dev.mikoto2000.rei.core.chat.AgentRunContext owner(Prompt prompt) {
    if (prompt.getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null) {
      var values = options.getToolContext();
      if (values.get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
          instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext run)
        return run.runContext();
      if (values.get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName())
          instanceof dev.mikoto2000.rei.core.chat.AgentRunContext run) return run;
    }
    return dev.mikoto2000.rei.core.chat.AgentRunScope.current();
  }

  /** 用途に応じたモデル利用で判断に用いるキャンセル済みの条件を確認する。 */
  private boolean cancelled(Prompt prompt) {
    return Thread.currentThread().isInterrupted()
        || prompt.getOptions()
                instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
            && options.getToolContext() != null
            && options
                    .getToolContext()
                    .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
                instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext run
            && run.isCancelled();
  }

  /** 用途に応じたモデル利用で用いる計測値を返す。 */
  private dev.mikoto2000.rei.timing.TimingExecution.SpanLease measurement(
      Prompt prompt, String request) {
    if (timing == null || !timing.enabled()) return null;
    var owner = owner(prompt);
    if (owner == null) return null;
    return measurement(prompt, request, null);
  }

  /** 用途に応じたモデル利用で用いる計測値を返す。 */
  private dev.mikoto2000.rei.timing.TimingExecution.SpanLease measurement(
      Prompt prompt, String request, dev.mikoto2000.rei.timing.TimingAttempt trace) {
    if (timing == null || !timing.enabled()) return null;
    var owner = owner(prompt);
    if (owner == null) return null;
    if (trace != null) trace.physicalSpan().set(request);
    var span =
        timing.startSpanWithId(
            owner.runId(),
            request,
            owner.runId(),
            trace == null ? request : trace.requestId(),
            trace == null ? request : Integer.toString(trace.number()),
            dev.mikoto2000.rei.timing.TimingRecorder.Category.LLM);
    span.prompt(observedFeature(prompt), prompt);
    return span;
  }

  /** 用途に応じたモデル利用で用いる観測機能を返す。 */
  private String observedFeature(Prompt prompt) {
    if (LlmFeature.CHAT.equals(feature)
        && prompt.getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null
        && "context-summary".equals(options.getToolContext().get("rei.modelCallPurpose")))
      return "context-summary";
    return feature;
  }

  /** 状況を判断する根拠を得るため、文書の分割単位を観測する。 */
  private void observeChunk(
      Prompt prompt,
      dev.mikoto2000.rei.timing.TimingExecution.SpanLease measured,
      ChatResponse response) {
    if (measured == null) return;
    timing.safely(
        () -> {
          measured.mark(dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_FRAMEWORK_CHUNK);
          var owner = owner(prompt);
          if (owner != null)
            timing.markRun(
                owner.runId(),
                dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_FRAMEWORK_CHUNK);
          var generation = response == null ? null : response.getResult();
          var output = generation == null ? null : generation.getOutput();
          var content = output == null ? null : output.getText();
          if (content != null && !content.isEmpty()) {
            measured.mark(dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_GENERATION_TEXT);
            if (owner != null)
              timing.markRun(
                  owner.runId(),
                  dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_GENERATION_TEXT);
          }
          observeUsage(measured, response);
        });
  }

  /** 状況を判断する根拠を得るため、使用状況を観測する。 */
  private void observeUsage(
      dev.mikoto2000.rei.timing.TimingExecution.SpanLease measured, ChatResponse response) {
    if (measured == null) return;
    timing.safely(
        () -> {
          if (response != null
              && response.getMetadata() != null
              && response.getMetadata().getUsage() != null
              && !(response.getMetadata().getUsage()
                  instanceof org.springframework.ai.chat.metadata.EmptyUsage)) {
            var usage = response.getMetadata().getUsage();
            measured.usage(
                usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue(),
                usage.getCompletionTokens() == null
                    ? null
                    : usage.getCompletionTokens().longValue(),
                null,
                null);
            measured.cacheUsage(
                usage.getNativeUsage() instanceof com.openai.models.completions.CompletionUsage
                    ? "openai-compatible"
                    : "UNKNOWN",
                response.getMetadata().getModel(),
                usage.getCacheReadInputTokens());
          }
        });
  }

  /** 用途に応じたモデル利用で用いる通知先を返す。 */
  private AgentEventPublisher publisher(Prompt prompt) {
    dev.mikoto2000.rei.core.chat.AgentRunContext owner = null;
    if (prompt.getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
            instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)
      owner = execution.runContext();
    var captured = owner;
    if (captured == null
        && prompt.getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null
        && options
                .getToolContext()
                .get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName())
            instanceof dev.mikoto2000.rei.core.chat.AgentRunContext context) {
      return event -> eventPublisher.publish(event.withOwnership(context));
    }
    return event -> eventPublisher.publish(event.withOwnership(captured));
  }

  /** 用途に応じたモデル利用で用いる経過時間ミリ秒数を返す。 */
  private long elapsedMillis(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }
}
