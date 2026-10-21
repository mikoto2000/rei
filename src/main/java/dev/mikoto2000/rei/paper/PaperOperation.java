package dev.mikoto2000.rei.paper;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 論文の調査と整理で受け渡す論文操作を表す。
 *
 * @param sessionId 会話の識別子
 * @param execution 実行
 * @param deadlineNanos 期限ナノ秒数
 */
public record PaperOperation(String sessionId, RunExecutionContext execution, long deadlineNanos) {
  /** 論文の調査と整理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public PaperOperation(String sessionId, RunExecutionContext execution) {
    this(sessionId, execution, Long.MAX_VALUE);
  }

  /** 指定された期限を反映した情報を作成する。 */
  public PaperOperation withDeadline(java.time.Duration timeout) {
    return new PaperOperation(
        sessionId,
        execution,
        Math.min(
            deadlineNanos,
            new dev.mikoto2000.rei.http.FetchOperation(() -> {}, Long.MAX_VALUE)
                .withTimeout(timeout)
                .deadlineNanos()));
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
    if (execution != null) execution.checkActive();
    if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos)
      throw new PaperException(PaperException.Code.PROVIDER_TIMEOUT, "処理がタイムアウトしました");
  }

  /** 必要な状態が整ってから先へ進むため、論文操作を待つ。 */
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
