package dev.mikoto2000.rei.core.chat;

import java.util.concurrent.CancellationException;

/**
 * 会話の実行に必要な実行キャンセルを扱う。
 *
 * <p>Cancellation is control flow, including interruption wrapped by an HTTP client.
 */
public final class RunCancellation {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private RunCancellation() {}

  /** 後続の操作を進められるか判断するため、活動中を確認する。 */
  public static void checkActive(org.springframework.ai.chat.prompt.Prompt prompt) {
    if (prompt.getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
            instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)
      execution.checkActive();
  }

  /** 非同期境界を越えた処理が実際に戻るまで、Run の資源として保持する。 */
  public static dev.mikoto2000.rei.core.service.CommandCancellationService.ChildLease enterChild(
      org.springframework.ai.chat.prompt.Prompt prompt) {
    if (prompt.getOptions()
        instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
      var values = options.getToolContext();
      if (values == null) return () -> {};
      var service =
          values.get(dev.mikoto2000.rei.core.service.CommandCancellationService.class.getName());
      var context = values.get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY);
      if (service instanceof dev.mikoto2000.rei.core.service.CommandCancellationService cancellation
          && context instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution) {
        var owner = execution.runContext();
        if (owner != null) return cancellation.enterChild(owner.runId());
      }
    }
    return () -> {};
  }

  /** response 側の非同期アドバイザーも同じ Run の所有資源として保持する。 */
  public static dev.mikoto2000.rei.core.service.CommandCancellationService.ChildLease enterChild(
      java.util.Map<String, Object> values) {
    var service =
        values.get(dev.mikoto2000.rei.core.service.CommandCancellationService.class.getName());
    var owner = values.get(AgentRunContext.class.getName());
    if (service instanceof dev.mikoto2000.rei.core.service.CommandCancellationService cancellation
        && owner instanceof AgentRunContext context)
      return cancellation.enterChild(context.runId());
    return () -> {};
  }

  /** 会話の実行において、キャンセルかどうかを判定する。 */
  public static boolean isCancellation(Throwable error) {
    for (Throwable cause : causes(error)) {
      if (cause instanceof InterruptedException
          || cause instanceof CancellationException
          || reactor.core.Exceptions.isCancel(cause)) return true;
    }
    return false;
  }

  /** 会話の実行に関するpropagateを処理する。 */
  public static void propagate(Throwable error) {
    if (!isCancellation(error) && !Thread.currentThread().isInterrupted()) return;
    for (Throwable cause : causes(error)) {
      if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    var cancelled = new CancellationException("chat run cancelled");
    if (error != null) cancelled.initCause(error);
    throw cancelled;
  }

  /** 会話の実行で用いる原因一覧を返す。 */
  private static java.util.List<Throwable> causes(Throwable error) {
    var causes = new java.util.ArrayList<Throwable>();
    var seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = error;
        cause != null && causes.size() < 32 && seen.add(cause);
        cause = cause.getCause()) causes.add(cause);
    return causes;
  }
}
