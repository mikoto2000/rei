package dev.mikoto2000.rei.core.chat;

import java.util.concurrent.CancellationException;

/** Cancellation is control flow, including interruption wrapped by an HTTP client. */
public final class RunCancellation {
  private RunCancellation() {}

  public static void checkActive(org.springframework.ai.chat.prompt.Prompt prompt) {
    if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
            instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution) execution.checkActive();
  }

  public static boolean isCancellation(Throwable error) {
    for (Throwable cause : causes(error)) {
      if (cause instanceof InterruptedException || cause instanceof CancellationException
          || reactor.core.Exceptions.isCancel(cause)) return true;
    }
    return false;
  }

  public static void propagate(Throwable error) {
    if (!isCancellation(error) && !Thread.currentThread().isInterrupted()) return;
    for (Throwable cause : causes(error)) {
      if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    var cancelled = new CancellationException("chat run cancelled");
    if (error != null) cancelled.initCause(error);
    throw cancelled;
  }

  private static java.util.List<Throwable> causes(Throwable error) {
    var causes=new java.util.ArrayList<Throwable>();
    var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
    for(Throwable cause=error;cause!=null && causes.size()<32 && seen.add(cause);cause=cause.getCause())causes.add(cause);
    return causes;
  }
}
