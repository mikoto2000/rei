package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.event.CredentialRedactor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 音声補正のユースケースを実現する。
 *
 * <p>Bounded admission, enqueue-to-completion deadline, exactly one outcome, and independent
 * late-result invalidation.
 */
public final class VoiceCorrectionService implements AutoCloseable {
  /** 音声による対話に必要な言語モデルを扱う。 */
  @FunctionalInterface
  public interface Llm {
    /** 音声による対話の要求を実行し、呼び出し元へ結果を返す。 */
    String call(String raw, VoiceCorrectionContext.Snapshot context) throws Exception;
  }

  /** この機能の動作設定。 */
  private final VoiceCorrectionProperties properties;

  /** 文脈一覧。 */
  private final Function<ConversationInput, VoiceCorrectionContext.Snapshot> contexts;

  /** 言語モデル。 */
  private final Llm llm;

  /** イベント一覧。 */
  private final VoiceEventPublisher events;

  /** 検証の窓口。 */
  private final VoiceCorrectionValidator validator = new VoiceCorrectionValidator();

  /** 実行の担当一覧。 */
  private final ThreadPoolExecutor workers;

  /** 期限一覧。 */
  private final ScheduledExecutorService deadlines;

  /** 経過時間を測る時刻の取得元。 */
  private final LongSupplier ticks;

  /** 実行する作業一覧。 */
  private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

  /** 終了済み。 */
  private volatile boolean closed;

  /**
   * 音声による対話で受け渡す追跡情報を表す。
   *
   * @param inputId 入力識別子
   * @param original 元の
   * @param correctionInput 補正入力
   * @param candidate 候補
   * @param adopted 採用した内容
   * @param submitted 受付済み
   * @param reason 理由
   * @param atNanos 日時ナノ秒数
   */
  public record Trace(
      UUID inputId,
      String original,
      String correctionInput,
      String candidate,
      String adopted,
      String submitted,
      String reason,
      long atNanos) {}

  /** 要求と実行を関連付ける追跡情報。 */
  private final Map<UUID, Trace> traces = new LinkedHashMap<>();

  /** 音声による対話に関する追跡情報を処理する。 */
  private void trace(Job job, VoiceCorrectionValidator.Decision decision) {
    synchronized (traces) {
      traces.values().removeIf(t -> ticks.getAsLong() - t.atNanos() > TimeUnit.MINUTES.toNanos(2));
      if (traces.size() >= 8) traces.remove(traces.keySet().iterator().next());
      traces.put(
          job.input.inputId(),
          new Trace(
              job.input.inputId(),
              job.original,
              job.input.text(),
              job.candidate,
              decision.text(),
              null,
              decision.reason(),
              job.queuedAt));
    }
  }

  /** 音声による対話で用いるtracesを返す。 */
  public List<Trace> traces() {
    synchronized (traces) {
      traces.values().removeIf(t -> ticks.getAsLong() - t.atNanos() > TimeUnit.MINUTES.toNanos(2));
      return List.copyOf(traces.values());
    }
  }

  /** 音声による対話に関する受付済みを処理する。 */
  public void submitted(ConversationInput input) {
    synchronized (traces) {
      var trace = traces.get(input.inputId());
      if (trace != null) {
        traces.put(
            input.inputId(),
            new Trace(
                trace.inputId(),
                trace.original(),
                trace.correctionInput(),
                trace.candidate(),
                trace.adopted(),
                input.text(),
                trace.reason(),
                trace.atNanos()));
        events.publish(
            VoiceEventPublisher.Type.CORRECTION_TIMING,
            "agent_admission_additional_ms=" + elapsed(trace.atNanos()));
      }
    }
  }

  /** 音声による対話に必要な実行する作業を扱う。 */
  private final class Job {
    /** 入力。 */
    final ConversationInput input;

    /** 元の。 */
    final String original;

    /** 現在。 */
    final BooleanSupplier current;

    /** 結果。 */
    final CompletableFuture<VoiceCorrectionValidator.Decision> result = new CompletableFuture<>();

    /** 待機中日時。 */
    final long queuedAt = ticks.getAsLong();

    /** enqueued日時。 */
    volatile long enqueuedAt;

    /** タスク。 */
    volatile FutureTask<Void> task;

    /** タイムアウト。 */
    volatile ScheduledFuture<?> timeout;

    /** 候補。 */
    volatile String candidate;

    /** 完了済み。 */
    final java.util.concurrent.atomic.AtomicBoolean finished =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** 指定された値や利用先を使って、Jobを初期化する。 */
    Job(ConversationInput input, String original, BooleanSupplier current) {
      this.input = input;
      this.original = original;
      this.current = current;
    }

    /** 終了時に必要な状態を反映し、処理を完了させる。 */
    boolean finish(VoiceCorrectionValidator.Decision decision) {
      // Select one winner before recording diagnostics. Observers recheck the voice generation.
      if (!finished.compareAndSet(false, true)) return false;
      trace(this, decision);
      result.complete(decision);
      jobs.remove(input.inputId(), this);
      var deadline = timeout;
      if (deadline != null) deadline.cancel(false);
      events.publish(
          VoiceEventPublisher.Type.CORRECTION_RESULT,
          decision.reason() + " additional_ms=" + elapsed(queuedAt));
      return true;
    }

    /** 対象の実行をキャンセルする。 */
    boolean cancel() {
      if (!finish(validator.fallback(input.text(), "cancelled"))) return false;
      var pending = task;
      if (pending != null) {
        pending.cancel(true);
        workers.remove(pending);
      }
      return true;
    }
  }

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public VoiceCorrectionService(
      VoiceCorrectionProperties properties,
      Function<ConversationInput, VoiceCorrectionContext.Snapshot> contexts,
      Llm llm,
      VoiceEventPublisher events) {
    this(
        properties,
        contexts,
        llm,
        events,
        Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voice-correction-deadline")),
        System::nanoTime);
  }

  /** 指定された値や利用先を使って、VoiceCorrectionServiceを初期化する。 */
  VoiceCorrectionService(
      VoiceCorrectionProperties properties,
      Function<ConversationInput, VoiceCorrectionContext.Snapshot> contexts,
      Llm llm,
      VoiceEventPublisher events,
      ScheduledExecutorService deadlines,
      LongSupplier ticks) {
    properties.validate();
    this.properties = properties;
    this.contexts = contexts;
    this.llm = llm;
    this.events = events;
    this.deadlines = deadlines;
    this.ticks = ticks;
    workers =
        new ThreadPoolExecutor(
            properties.getMaxConcurrentRequests(),
            properties.getMaxConcurrentRequests(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(properties.getMaxQueuedRequests()),
            r -> daemon(r, "voice-correction"),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /** 音声による対話で用いる常駐を返す。 */
  private static Thread daemon(Runnable work, String name) {
    var thread = new Thread(work, name);
    thread.setDaemon(true);
    return thread;
  }

  /** 音声による対話で用いる経過時間を返す。 */
  private long elapsed(long start) {
    return Math.max(0, (ticks.getAsLong() - start) / 1_000_000);
  }

  /** 音声による対話で用いるcorrectを返す。 */
  public CompletionStage<VoiceCorrectionValidator.Decision> correct(
      ConversationInput input, BooleanSupplier current) {
    return correct(input, current, null);
  }

  /** 音声による対話で用いるcorrectを返す。 */
  public CompletionStage<VoiceCorrectionValidator.Decision> correct(
      ConversationInput input,
      BooleanSupplier current,
      Consumer<VoiceCorrectionValidator.Decision> completion) {
    return correct(input, input.text(), current, completion);
  }

  /** 音声による対話で用いるcorrectを返す。 */
  public CompletionStage<VoiceCorrectionValidator.Decision> correct(
      ConversationInput input,
      String asrOriginal,
      BooleanSupplier current,
      Consumer<VoiceCorrectionValidator.Decision> completion) {
    return correctInternal(input, Objects.requireNonNull(asrOriginal), current, completion);
  }

  /** 音声による対話で用いるimmediateを返す。 */
  private CompletionStage<VoiceCorrectionValidator.Decision> immediate(
      VoiceCorrectionValidator.Decision decision,
      Consumer<VoiceCorrectionValidator.Decision> completion) {
    if (completion != null) completion.accept(decision);
    return CompletableFuture.completedFuture(decision);
  }

  /** 音声による対話で用いるcorrect内部を返す。 */
  private CompletionStage<VoiceCorrectionValidator.Decision> correctInternal(
      ConversationInput input,
      String asrOriginal,
      BooleanSupplier current,
      Consumer<VoiceCorrectionValidator.Decision> completion) {
    String raw = input.text();
    if (!properties.isEnabled() || VoiceCorrectionValidator.controlOrReply(raw))
      return immediate(new VoiceCorrectionValidator.Decision(raw, "bypassed", false), completion);
    if (closed || !current.getAsBoolean())
      return immediate(validator.fallback(raw, "cancelled"), completion);
    if (raw.codePointCount(0, raw.length()) > properties.getMaxInputChars())
      return immediate(validator.fallback(raw, "input_limit"), completion);
    // Sending redacted ASR could change meaning; keep this utterance local instead.
    if (!CredentialRedactor.redact(raw).equals(raw))
      return immediate(validator.fallback(raw, "sensitive_input"), completion);
    var job = new Job(input, asrOriginal, current);
    var prior = jobs.putIfAbsent(input.inputId(), job);
    if (prior != null) {
      if (!prior.input.equals(input))
        throw new IllegalArgumentException("Conflicting correction identity");
      if (completion != null) prior.result.thenAccept(completion);
      return prior.result;
    }
    if (completion != null) job.result.thenAccept(completion);
    try {
      job.timeout =
          deadlines.schedule(
              () -> {
                if (!job.finish(validator.fallback(raw, "timeout"))) return;
                var pending = job.task;
                if (pending != null) {
                  pending.cancel(true);
                  workers.remove(pending);
                }
              },
              properties.getTimeoutMs(),
              TimeUnit.MILLISECONDS);
      // Snapshot before entering the executor queue; no worker ThreadLocal is consulted.
      var context = contexts.apply(input);
      job.enqueuedAt = ticks.getAsLong();
      job.task =
          new FutureTask<>(
              () -> {
                if (job.result.isDone()) return null;
                if (!current.getAsBoolean()) {
                  job.cancel();
                  return null;
                }
                long started = ticks.getAsLong();
                events.publish(
                    VoiceEventPublisher.Type.CORRECTION_TIMING,
                    "queue_ms="
                        + elapsed(job.enqueuedAt)
                        + " context_ms="
                        + Math.max(0, (job.enqueuedAt - job.queuedAt) / 1_000_000));
                try {
                  String candidate = llm.call(raw, context);
                  if (!job.finished.get()
                      && candidate != null
                      && candidate.length()
                          <= Math.min(65536, properties.getMaxInputChars() * 16 + 2048))
                    job.candidate = candidate;
                  long validation = ticks.getAsLong();
                  var decision = validator.validate(raw, candidate, properties.getMaxInputChars());
                  events.publish(
                      VoiceEventPublisher.Type.CORRECTION_TIMING,
                      "llm_ms="
                          + Math.max(0, (validation - started) / 1_000_000)
                          + " validation_ms="
                          + elapsed(validation));
                  if (current.getAsBoolean()) job.finish(decision);
                  else job.cancel();
                } catch (Exception failure) {
                  job.finish(validator.fallback(raw, "llm_failure"));
                } // Never publish exception text.
                return null;
              });
      if (!job.result.isDone()) workers.execute(job.task);
    } catch (RejectedExecutionException full) {
      job.finish(validator.fallback(raw, "queue_full"));
    } catch (RuntimeException unavailable) {
      job.finish(validator.fallback(raw, "context_failure"));
    }
    return job.result;
  }

  /** 利用者が不要とした実行を止めるため、全対象をキャンセルする。 */
  public void cancelAll() {
    for (var job : List.copyOf(jobs.values())) job.cancel();
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public List<ConversationInput> pending() {
    return jobs.values().stream()
        .filter(job -> !job.finished.get() && job.current.getAsBoolean())
        .sorted(java.util.Comparator.comparingLong(job -> job.queuedAt))
        .map(job -> job.input)
        .toList();
  }

  /** 対象の実行をキャンセルする。 */
  public boolean cancel(UUID id) {
    var job = jobs.get(id);
    return job != null && job.cancel();
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    closed = true;
    cancelAll();
    deadlines.shutdownNow();
    workers.shutdownNow();
  }
}
