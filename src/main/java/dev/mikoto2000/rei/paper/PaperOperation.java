package dev.mikoto2000.rei.paper;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.util.concurrent.*;
import org.springframework.ai.chat.model.ToolContext;

public record PaperOperation(String sessionId, RunExecutionContext execution, long deadlineNanos) {
  public PaperOperation(String sessionId, RunExecutionContext execution) { this(sessionId, execution, Long.MAX_VALUE); }
  public PaperOperation withDeadline(java.time.Duration timeout) {
    return new PaperOperation(sessionId, execution,
        Math.min(deadlineNanos, new dev.mikoto2000.rei.http.FetchOperation(() -> {}, Long.MAX_VALUE).withTimeout(timeout).deadlineNanos()));
  }
  public static PaperOperation local(String session) {
    return new PaperOperation(session, null);
  }

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

  public void check() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    if (execution != null) execution.checkActive();
    if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos)
      throw new PaperException(PaperException.Code.PROVIDER_TIMEOUT, "処理がタイムアウトしました");
  }

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
