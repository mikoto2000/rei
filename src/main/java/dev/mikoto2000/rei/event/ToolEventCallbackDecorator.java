package dev.mikoto2000.rei.event;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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

  private static final Pattern QUOTED_SECRET = Pattern.compile(
      "(?i)(\\\"(?:api[_-]?key|access[_-]?token|token|password|secret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");

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
    String toolName = delegate.getToolDefinition().name();
    String toolCallId = resolveToolCallId(toolContext);
    long startedAtNanos = System.nanoTime();

    eventPublisher.publish(eventFactory.toolStarted(toolCallId, toolName, summarize(toolInput)));
    try {
      String result = caller.get();
      long duration = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
      eventPublisher.publish(eventFactory.toolCompleted(toolCallId, toolName, duration, summarize(result)));
      return result;
    } catch (RuntimeException e) {
      eventPublisher.publish(eventFactory.toolFailed(toolCallId, toolName, ErrorInformation.from(e)));
      throw e;
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
    String sanitized = QUOTED_SECRET.matcher(value).replaceAll("$1[REDACTED]$2");
    int maxLength = 120;
    if (sanitized.length() <= maxLength) {
      return sanitized;
    }
    return sanitized.substring(0, maxLength) + "... (" + sanitized.length() + " chars)";
  }
}
