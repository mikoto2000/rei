package dev.mikoto2000.rei.paper;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;

/** 論文操作の会話、期限、実行中の確認操作をまとめる。 */
public final class PaperOperation {
  private final String sessionId;
  private final long deadlineNanos;
  private final Runnable checkExecution;
  private final Supplier<AgentRunContext> runContext;

  /** 実行中の確認とプロジェクト参照に必要な操作を受け取る。 */
  public PaperOperation(String sessionId, RunExecutionContext execution, long deadlineNanos) {
    this(
        sessionId,
        deadlineNanos,
        () -> {
          if (execution != null) execution.checkActive();
        },
        () -> execution == null ? null : execution.runContext());
  }

  private PaperOperation(
      String sessionId,
      long deadlineNanos,
      Runnable checkExecution,
      Supplier<AgentRunContext> runContext) {
    this.sessionId = sessionId;
    this.deadlineNanos = deadlineNanos;
    this.checkExecution = checkExecution;
    this.runContext = runContext;
  }

  /** 操作の会話識別子を返す。 */
  public String sessionId() {
    return sessionId;
  }

  /** 操作の期限を返す。 */
  public long deadlineNanos() {
    return deadlineNanos;
  }

  /** プロジェクトの境界確認に使用する実行情報を返す。 */
  AgentRunContext runContext() {
    return runContext.get();
  }

  /** 論文の調査と整理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public PaperOperation(String sessionId, RunExecutionContext execution) {
    this(sessionId, execution, Long.MAX_VALUE);
  }

  /** 指定された期限を反映した情報を作成する。 */
  public PaperOperation withDeadline(java.time.Duration timeout) {
    return new PaperOperation(
        sessionId,
        Math.min(
            deadlineNanos,
            new dev.mikoto2000.rei.http.FetchOperation(() -> {}, Long.MAX_VALUE)
                .withTimeout(timeout)
                .deadlineNanos()),
        checkExecution,
        runContext);
  }

  /** 論文の調査と整理で用いるローカルを返す。 */
  public static PaperOperation local(String session) {
    return new PaperOperation(session, null);
  }

  /** 論文の調査と整理で利用する情報を、受け取った形式から変換する。 */
  public static PaperOperation from(ToolContext context) {
    var execution =
        context == null
            ? null
            : (RunExecutionContext) context.getContext().get(RunExecutionContext.KEY);
    var run =
        execution == null
            ? dev.mikoto2000.rei.core.chat.AgentRunScope.current()
            : execution.runContext();
    return new PaperOperation(run == null ? null : run.conversationId(), execution);
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public void check() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    checkExecution.run();
    if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos)
      throw new PaperException(PaperException.Code.PROVIDER_TIMEOUT, "処理がタイムアウトしました");
  }

  /** 必要な状態が整ってから先へ進むため、論文操作を待つ。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Preserves unchecked provider failures and operation control signals from the future.")
  public <T> T await(CompletableFuture<T> future, java.time.Duration timeout) {
    long deadline = Math.min(deadlineNanos, System.nanoTime() + timeout.toNanos());
    try {
      while (true) {
        check();
        if (System.nanoTime() > deadline)
          throw new PaperException(PaperException.Code.PROVIDER_TIMEOUT, "処理がタイムアウトしました");
        try {
          return future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException retry) {
        }
      }
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException();
    } catch (ExecutionException e) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(e);
      if (e.getCause() instanceof java.util.concurrent.TimeoutException)
        throw new PaperException(PaperException.Code.PROVIDER_TIMEOUT, "処理がタイムアウトしました", e);
      if (e.getCause() instanceof RuntimeException r) throw r;
      throw new PaperException(PaperException.Code.SEARCH_FAILED, "通信に失敗しました", e);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }
}
