package dev.mikoto2000.rei.timing;

import static dev.mikoto2000.rei.timing.TimingRecorder.Category;
import static dev.mikoto2000.rei.timing.TimingRecorder.Metric;
import static dev.mikoto2000.rei.timing.TimingRecorder.Status;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 実行時間の把握に必要な時間計測実行を扱う。
 *
 * <p>Optional observation; no additional execution, permission or budget engine.
 */
@Service
public class TimingExecution {
  /** 実行時間を記録する窓口。 */
  private final TimingRecorder recorder;

  /** 指定された値や利用先を使って、TimingExecutionを初期化する。 */
  public TimingExecution(TimingRecorder recorder) {
    this.recorder = recorder;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public boolean enabled() {
    try {
      return recorder != null && recorder.enabled();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** 実行時間の把握に関するsafelyを処理する。 */
  public void safely(Runnable observation) {
    try {
      observation.run();
    } catch (RuntimeException unavailable) {
      /* Observation never changes the operation. */
    }
  }

  /** 状況を判断する根拠を得るため、実行を観測する。 */
  public ChatExecutionResult observeRun(
      AgentRunContext owner, Supplier<ChatExecutionResult> operation) {
    if (!enabled() || owner == null) return operation.get();
    boolean own = false;
    try {
      own = recorder.beginRun(owner.runId(), owner.projectId(), owner.conversationId());
    } catch (RuntimeException unavailable) {
    }
    Status status = Status.INCOMPLETE;
    try {
      var result = operation.get();
      status = status(result);
      return result;
    } catch (RuntimeException failure) {
      status = status(failure);
      throw failure;
    } finally {
      if (own) {
        var terminal = status;
        safely(() -> recorder.finishRun(owner.runId(), terminal));
      }
    }
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public Status status(ChatExecutionResult result) {
    if (result == null) return Status.INCOMPLETE;
    if (result.stopCode().equals("run_timeout") || result.stopCode().equals("timeout"))
      return Status.TIMED_OUT;
    return switch (result.status()) {
      case SUCCESS -> Status.SUCCESS;
      case FAILED -> Status.FAILED;
      case CANCELLED -> Status.CANCELLED;
      case WAITING -> Status.INCOMPLETE;
    };
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public static Status status(Throwable failure) {
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
      if (failure instanceof java.util.concurrent.CancellationException
          || failure instanceof InterruptedException) return Status.CANCELLED;
      if (failure instanceof java.util.concurrent.TimeoutException
          || failure instanceof java.net.http.HttpTimeoutException) return Status.TIMED_OUT;
      if (failure instanceof java.io.IOException
          || failure instanceof com.openai.errors.OpenAIIoException) return Status.DISCONNECTED;
      if (failure.getCause() == failure) break;
    }
    return Status.FAILED;
  }

  /** 要求された処理を利用できるよう、計測区間を開始する。 */
  public SpanLease startSpan(
      String runId, String parent, String request, String attempt, Category category) {
    return startSpanWithId(runId, UUID.randomUUID().toString(), parent, request, attempt, category);
  }

  /** 要求された処理を利用できるよう、計測区間付き識別子を開始する。 */
  public SpanLease startSpanWithId(
      String runId, String id, String parent, String request, String attempt, Category category) {
    boolean recorded = false;
    if (enabled())
      try {
        recorded = recorder.beginSpan(runId, id, parent, request, attempt, category);
      } catch (RuntimeException unavailable) {
      }
    return new SpanLease(runId, id, recorded);
  }

  /** 実行時間の把握に必要な計測区間利用権を扱う。 */
  public final class SpanLease {
    /** 実行／識別子。 */
    private final String run, id;

    /** 記録済みかどうか。 */
    private final boolean recorded;

    /** 完了済み。 */
    private boolean finished;

    /** 指定された値や利用先を使って、SpanLeaseを初期化する。 */
    SpanLease(String run, String id, boolean recorded) {
      this.run = run;
      this.id = id;
      this.recorded = recorded;
    }

    /** 実行時間の把握で用いる識別子を返す。 */
    public String id() {
      return id;
    }

    /** 実行時間の把握に関するプロンプトを処理する。 */
    public void prompt(String feature, org.springframework.ai.chat.prompt.Prompt prompt) {
      if (recorded) safely(() -> recorder.prompt(run, id, feature, prompt));
    }

    /** 実行時間の把握に関するキャッシュ使用状況を処理する。 */
    public void cacheUsage(String provider, String model, Long cached) {
      if (recorded) safely(() -> recorder.cacheUsage(run, id, provider, model, cached));
    }

    /** 終了時に必要な状態を反映し、処理を完了させる。 */
    public synchronized void finish(Status status) {
      if (recorded && !finished) {
        finished = true;
        safely(() -> recorder.endSpan(run, id, status));
      }
    }

    /** 後続の判断に現在の状態を反映するため、計測区間利用権の状態を記録する。 */
    public void mark(Metric metric) {
      if (recorded) safely(() -> recorder.markSpan(run, id, metric));
    }

    /** 実行時間の把握に関する使用状況を処理する。 */
    public void usage(Long input, Long output, Long generated, Long duration) {
      if (recorded) safely(() -> recorder.usage(run, id, input, output, generated, duration));
    }
  }

  /** 後続の判断に現在の状態を反映するため、実行の状態を記録する。 */
  public void markRun(String runId, Metric metric) {
    if (enabled()) safely(() -> recorder.markRun(runId, metric));
  }
}
