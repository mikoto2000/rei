package dev.mikoto2000.rei.event;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolCallback の実行前後で Agent Event を発行する Decorator。
 *
 * <p>各 Tool 実装に個別追加するのではなく、共通実行境界である {@code call} をラップして
 * tool.started / tool.completed / tool.failed を発行する。
 * 結果全文は payload に格納せず、要約情報のみを保持する。</p>
 */
public class ToolEventCallbackDecorator implements ToolCallback {

  private final ToolCallback delegate;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;

  public ToolEventCallbackDecorator(ToolCallback delegate, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public String call(String toolInput) {
    return emitAndCall(toolInput, null, () -> delegate.call(toolInput));
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    return emitAndCall(toolInput, toolContext, () -> delegate.call(toolInput, toolContext));
  }

  private String emitAndCall(String toolInput, ToolContext toolContext, Supplier<String> caller) {
    var owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (toolContext != null && toolContext.getContext().get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName())
        instanceof dev.mikoto2000.rei.core.chat.AgentRunContext captured) owner = captured;
    var modelBudget=dev.mikoto2000.rei.llm.ModelCallBudgetScope.current();
    if(toolContext!=null&&toolContext.getContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
        instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)modelBudget=execution.modelCallBudget();
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner);
        var budgetScope=dev.mikoto2000.rei.llm.ModelCallBudgetScope.open(modelBudget)) {
    String toolName = delegate.getToolDefinition().name();
    String toolCallId = resolveToolCallId(toolContext);
    long startedAtNanos = System.nanoTime();

    eventPublisher.publishBoundary(eventFactory.toolStarted(toolCallId, toolName, summarize(toolInput)));
    String result;
    try {
      // Cancellation can arrive while the durable STARTED boundary is being written.
      if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
      if(toolContext!=null&&toolContext.getContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
          instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)execution.checkActive();
      result = caller.get();
    } catch (RuntimeException e) {
      eventPublisher.publishBoundary(eventFactory.toolFailed(toolCallId, toolName,
          new ErrorInformation(e.getClass().getSimpleName(), summarize(e.getMessage()), null)));
      throw e;
    }
    long duration = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    // Storage failure after the side effect must leave STARTED, never falsely record FAILED.
    eventPublisher.publishBoundary(eventFactory.toolCompleted(toolCallId, toolName, duration, summarize(result)));
    return result;
    }
  }

  /** ToolContext から toolCallId を取得する。無ければ UUID を生成する。 */
  private String resolveToolCallId(ToolContext toolContext) {
    if (toolContext != null && toolContext.getContext() != null) {
      Object id = toolContext.getContext().get("toolCallId");
      if (id != null) {
        return id.toString();
      }
    }
    return UUID.randomUUID().toString();
  }

  /** 引数・結果の要約。全文を payload に入れないための summary を返す。 */
  private String summarize(String value) {
    if (value == null) {
      return "";
    }
    String sanitized = CredentialRedactor.redact(value);
    int maxLength = 120;
    if (sanitized.length() <= maxLength) {
      return sanitized;
    }
    return sanitized.substring(0, maxLength) + "... (" + sanitized.length() + " chars)";
  }
}
