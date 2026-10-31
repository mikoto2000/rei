package dev.mikoto2000.rei.core.chat;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import dev.mikoto2000.rei.llm.OutputLimitDetector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 会話の実行に必要な上限内に収めたツール繰り返しを扱う。
 *
 * <p>Ephemeral explicit loop: no advisors, persistence, planner, or ambient tool resolution.
 */
public final class BoundedToolLoop {
  /** ツール一覧。 */
  private final ToolLoopSupport tools = new ToolLoopSupport();

  /** 文脈の組み立て。 */
  private final dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler;

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public BoundedToolLoop() {
    this(null);
  }

  /** 指定された値や利用先を使って、BoundedToolLoopを初期化する。 */
  public BoundedToolLoop(dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler) {
    this.assembler = assembler;
  }

  /** 会話の実行に必要な共有予算上限超過を扱う。 */
  public static final class SharedBudgetExceeded extends RuntimeException {}

  /** 会話の実行に必要な最大手順一覧上限超過を扱う。 */
  public static final class MaxStepsExceeded extends RuntimeException {}

  /** 会話の実行において、一時的なモデル失敗情報かどうかを判定する。 */
  private static boolean isTransientModelFailure(Throwable error) {
    // Spring AI 2 streams SDK failures through async completion wrappers. Do not
    // follow arbitrary causes: an application failure may have a transient cause.
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) {
      error = error.getCause();
    }
    if (error instanceof OpenAIIoException || error instanceof OpenAIRetryableException)
      return true;
    if (error instanceof OpenAIServiceException service) {
      String retryHint = service.headers().values("X-Should-Retry").stream().findFirst().orElse("");
      if ("false".equals(retryHint)) return false;
      if ("true".equals(retryHint)) return true;
      int status = service.statusCode();
      return status == 408 || status == 409 || status == 429 || status >= 500;
    }
    return false;
  }

  /**
   * 会話の実行に必要なモデル再試行回数を扱う。
   *
   * <p>One invocation-wide retry allowance, also shared by repair cycles and the tool-free judge.
   */
  public static final class ModelRetries {
    /** 残量。 */
    private final AtomicInteger remaining;

    /** 試行回数。 */
    private final AtomicInteger attempts = new AtomicInteger();

    /** 失敗一覧。 */
    private final List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 指定された値や利用先を使って、ModelRetriesを初期化する。 */
    public ModelRetries(int max) {
      if (max < 0 || max > 3) throw new IllegalArgumentException("Model retries must be 0 to 3");
      remaining = new AtomicInteger(max);
    }

    /** 会話の実行において、予約に該当するか判定する。 */
    private boolean reserve() {
      return remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
    }

    /** 処理を継続できなかった理由を、失敗情報として表す。 */
    private void failure(Throwable error, boolean received) {
      if (isTransientModelFailure(error) && failures.size() < 4)
        failures.add(
            received
                ? "TRANSIENT_MODEL_FAILURE_AFTER_PARTIAL_RESPONSE"
                : "TRANSIENT_MODEL_FAILURE_BEFORE_RESPONSE");
    }

    /** 会話の実行で用いる試行回数を返す。 */
    public int attempts() {
      return attempts.get();
    }

    /** 過去の記録を、条件に従って参照できる形で返す。 */
    public List<String> history() {
      return List.copyOf(failures);
    }
  }

  /**
   * 会話の実行で受け渡す最終結果を表す。
   *
   * @param output 出力
   * @param history 履歴
   */
  public record Outcome(String output, List<Message> history) {
    /** 受け取った情報からOutcomeを作成する。 */
    public Outcome {
      history = List.copyOf(history);
    }
  }

  /** 会話の実行の操作を実行する。 */
  public Mono<String> run(
      ChatModel model, Prompt prompt, int maxSteps, AgentRunContext owner, Runnable checkActive) {
    return Mono.defer(
            () -> runWithHistory(model, prompt, new AtomicInteger(maxSteps), owner, checkActive))
        .map(Outcome::output);
  }

  /** 依頼された操作を進めるため、付き履歴を実行する。 */
  public Mono<Outcome> runWithHistory(
      ChatModel model,
      Prompt prompt,
      AtomicInteger remaining,
      AgentRunContext owner,
      Runnable checkActive) {
    return runWithHistory(model, prompt, remaining, owner, checkActive, null);
  }

  /** 依頼された操作を進めるため、付き履歴を実行する。 */
  public Mono<Outcome> runWithHistory(
      ChatModel model,
      Prompt prompt,
      AtomicInteger remaining,
      AgentRunContext owner,
      Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation) {
    return runWithHistory(
        model, prompt, remaining, owner, checkActive, reservation, new ModelRetries(0));
  }

  /** 依頼された操作を進めるため、付き履歴を実行する。 */
  public Mono<Outcome> runWithHistory(
      ChatModel model,
      Prompt prompt,
      AtomicInteger remaining,
      AgentRunContext owner,
      Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,
      ModelRetries retries) {
    return iteration(
        model,
        dev.mikoto2000.rei.llm.StableToolDefinitions.prompt(prompt),
        remaining,
        owner,
        checkActive,
        reservation,
        retries);
  }

  /** 会話の実行で用いる応答を返す。 */
  private Mono<ChatResponse> response(
      ChatModel model,
      Prompt prompt,
      AtomicInteger remaining,
      Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,
      ModelRetries retries,
      boolean retrying,
      dev.mikoto2000.rei.timing.TimingAttempt previous) {
    return Mono.defer(
        () -> {
          checkActive.run();
          var timing =
              model instanceof dev.mikoto2000.rei.timing.TimingAwareModel observed
                  ? observed.timing()
                  : null;
          var trace =
              timing != null && timing.enabled()
                  ? (previous == null
                      ? new dev.mikoto2000.rei.timing.TimingAttempt(
                          UUID.randomUUID().toString(), 1, new AtomicReference<>())
                      : previous)
                  : null;
          if (remaining.getAndDecrement() <= 0) return Mono.error(new MaxStepsExceeded());
          if (reservation != null && !reservation.tryReserve())
            return Mono.error(new SharedBudgetExceeded());
          checkActive.run();
          AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
          var started = new java.util.concurrent.atomic.AtomicBoolean();
          var reported = new java.util.concurrent.atomic.AtomicBoolean();
          var received = new java.util.concurrent.atomic.AtomicBoolean();
          return new MessageAggregator()
              .aggregate(
                  reactor.core.publisher.Flux.defer(
                          () -> {
                            checkActive.run();
                            started.set(true);
                            if (retrying) retries.attempts.incrementAndGet();
                            var stream = model.stream(prompt);
                            return trace == null
                                ? stream
                                : stream.contextWrite(
                                    context ->
                                        context.put(
                                            dev.mikoto2000.rei.timing.TimingAttempt.KEY, trace));
                          })
                      .doOnNext(chunk -> received.set(true))
                      .doOnError(error -> retries.failure(error, received.get())),
                  aggregated::set)
              .then(
                  Mono.defer(
                      () -> {
                        checkActive.run();
                        var response = aggregated.get();
                        if (response == null || response.getResult() == null)
                          return Mono.error(new IllegalStateException("Empty response"));
                        if (reservation != null) {
                          reported.set(true);
                          var usage = response.getMetadata().getUsage();
                          reservation.recordTotalTokens(
                              usage == null ? null : usage.getTotalTokens());
                        }
                        if (OutputLimitDetector.isOutputLimitReached(response))
                          return Mono.error(new IllegalStateException("Output limit"));
                        return Mono.just(response);
                      }))
              .doOnError(
                  error -> {
                    if (reservation != null
                        && reservation.tokenLimitEnabled()
                        && started.get()
                        && !reported.getAndSet(true)) reservation.recordTotalTokens(null);
                  })
              .doFinally(
                  signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL
                        && reservation != null
                        && reservation.tokenLimitEnabled()
                        && started.get()
                        && !reported.getAndSet(true)) {
                      try {
                        reservation.recordTotalTokens(null);
                      } catch (RuntimeException stopped) {
                        /* Accounting state prevents further calls. */
                      }
                    }
                  })
              .onErrorResume(
                  error -> {
                    if (isTransientModelFailure(error) && !received.get()) {
                      checkActive.run();
                      if (retries.reserve()) {
                        var delay = Mono.delay(java.time.Duration.ofMillis(100));
                        if (trace != null
                            && prompt.getOptions() instanceof ToolCallingChatOptions options
                            && options.getToolContext() != null) {
                          var captured =
                              options
                                  .getToolContext()
                                  .get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY);
                          AgentRunContext owner =
                              captured
                                      instanceof
                                      dev.mikoto2000.rei.core.stagnation.RunExecutionContext
                                          execution
                                  ? execution.runContext()
                                  : options.getToolContext().get(AgentRunContext.class.getName())
                                          instanceof AgentRunContext context
                                      ? context
                                      : null;
                          if (owner != null) {
                            String parent = trace.physicalSpan().get();
                            var span =
                                timing.startSpan(
                                    owner.runId(),
                                    parent == null ? owner.runId() : parent,
                                    trace.requestId(),
                                    Integer.toString(trace.number() + 1),
                                    dev.mikoto2000.rei.timing.TimingRecorder.Category.RETRY);
                            delay =
                                delay
                                    .doOnSuccess(
                                        value ->
                                            span.finish(
                                                dev.mikoto2000.rei.timing.TimingRecorder.Status
                                                    .SUCCESS))
                                    .doOnError(
                                        failure ->
                                            span.finish(
                                                dev.mikoto2000.rei.timing.TimingExecution.status(
                                                    failure)))
                                    .doOnCancel(
                                        () ->
                                            span.finish(
                                                dev.mikoto2000.rei.timing.TimingRecorder.Status
                                                    .CANCELLED));
                          }
                        }
                        return delay.then(
                            response(
                                model,
                                prompt,
                                remaining,
                                checkActive,
                                reservation,
                                retries,
                                true,
                                trace == null ? null : trace.next()));
                      }
                    }
                    return Mono.error(error);
                  });
        });
  }

  /** 会話の実行で用いる繰り返しを返す。 */
  private Mono<Outcome> iteration(
      ChatModel model,
      Prompt prompt,
      AtomicInteger remaining,
      AgentRunContext owner,
      Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,
      ModelRetries retries) {
    return Mono.fromCallable(
            () -> {
              if (assembler == null) return prompt;
              var summaryBudget =
                  new dev.mikoto2000.rei.llm.ModelCallBudget() {
                    /** 会話の実行の操作を実行する。 */
                    public void run() {
                      checkActive.run();
                      if (remaining.getAndDecrement() <= 0) throw new MaxStepsExceeded();
                      if (reservation != null && !reservation.tryReserve())
                        throw new SharedBudgetExceeded();
                    }

                    /** トークン数による実行予算の制限が有効かどうかを返す。 */
                    public boolean tokenLimitEnabled() {
                      return reservation != null && reservation.tokenLimitEnabled();
                    }

                    /** 使用したトークン数を実行予算へ記録する。 */
                    public void recordTotalTokens(Integer tokens) {
                      if (reservation != null) reservation.recordTotalTokens(tokens);
                      checkActive.run();
                    }
                  };
              try (var scope = AgentRunScope.open(owner);
                  var budget = dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(summaryBudget)) {
                return assembler.assemble(
                    prompt, owner.conversationId(), owner.runId(), checkActive);
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(
            projected ->
                response(
                        model, projected, remaining, checkActive, reservation, retries, false, null)
                    .flatMap(
                        response ->
                            Mono.defer(
                                () -> {
                                  checkActive.run();
                                  var readingUsage = response.getMetadata().getUsage();
                                  if (readingUsage != null
                                      && readingUsage.getPromptTokens() != null
                                      && readingUsage.getPromptTokens() > 0)
                                    dev.mikoto2000.rei.tooldelivery.ReadingMetrics.add(
                                        owner,
                                        "providerInputTokens",
                                        readingUsage.getPromptTokens());
                                  else
                                    dev.mikoto2000.rei.tooldelivery.ReadingMetrics.add(
                                        owner, "providerInputTokensUnavailable", 1);
                                  if (assembler != null)
                                    assembler.confirmedDelivery(projected, owner);
                                  if (!response.hasToolCalls()) {
                                    var generation =
                                        Objects.requireNonNull(
                                            response.getResult(), "Missing model result");
                                    var history = new ArrayList<Message>(prompt.getInstructions());
                                    history.add(generation.getOutput());
                                    return Mono.just(
                                        new Outcome(
                                            Objects.toString(generation.getOutput().getText(), ""),
                                            history));
                                  }
                                  var options =
                                      Objects.requireNonNull(
                                          (ToolCallingChatOptions) prompt.getOptions(),
                                          "Missing tool options");
                                  Set<String> allowed = new HashSet<>();
                                  Objects.requireNonNull(
                                          options.getToolCallbacks(), "Missing tool callbacks")
                                      .forEach(
                                          tool -> allowed.add(tool.getToolDefinition().name()));
                                  // Validate the entire batch before any side effect. Never let a
                                  // resolver find ambient tools.
                                  for (var generation : response.getResults())
                                    for (var call : generation.getOutput().getToolCalls()) {
                                      if (!allowed.contains(call.name()))
                                        return Mono.error(
                                            new IllegalArgumentException("Unrequested tool"));
                                    }
                                  return Mono.fromCallable(
                                          () -> {
                                            try (var scope = AgentRunScope.open(owner)) {
                                              checkActive.run();
                                              return tools.execute(
                                                  dev.mikoto2000.rei.tooldelivery.FileReadContext
                                                      .withProjection(prompt, projected),
                                                  response);
                                            }
                                          })
                                      .subscribeOn(Schedulers.boundedElastic())
                                      .flatMap(
                                          result -> {
                                            checkActive.run();
                                            if (result.returnDirect()) {
                                              var output =
                                                  ToolExecutionResult.buildGenerations(result)
                                                      .getFirst()
                                                      .getOutput();
                                              var history =
                                                  new ArrayList<Message>(
                                                      result.conversationHistory());
                                              history.add(output);
                                              return Mono.just(
                                                  new Outcome(output.getText(), history));
                                            }
                                            return iteration(
                                                model,
                                                new Prompt(
                                                    result.conversationHistory(),
                                                    prompt.getOptions()),
                                                remaining,
                                                owner,
                                                checkActive,
                                                reservation,
                                                retries);
                                          });
                                })));
  }
}
