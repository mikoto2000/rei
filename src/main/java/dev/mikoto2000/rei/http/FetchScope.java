package dev.mikoto2000.rei.http;

import org.springframework.ai.chat.model.ToolContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

/** Capture explicitly before crossing executor boundaries. */
public final class FetchScope implements AutoCloseable {
  private static final ThreadLocal<FetchOperation> CURRENT = new ThreadLocal<>();
  private final FetchOperation previous;
  private FetchScope(FetchOperation operation) { previous = CURRENT.get(); CURRENT.set(operation); }
  public static FetchOperation current() { var operation = CURRENT.get(); return operation == null ? FetchOperation.active() : operation; }
  public static FetchScope enter(FetchOperation operation) { return new FetchScope(operation); }
  public static FetchScope enter(ToolContext context) {
    Object execution = context == null ? null : context.getContext().get(RunExecutionContext.KEY);
    return enter(execution instanceof RunExecutionContext run
        ? new FetchOperation(run::checkActive, Long.MAX_VALUE) : current());
  }
  public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
