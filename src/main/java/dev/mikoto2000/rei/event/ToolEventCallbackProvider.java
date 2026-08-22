package dev.mikoto2000.rei.event;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * ToolCallbackProvider を Decorator でラップし、各 ToolCallback を
 * {@link ToolEventCallbackDecorator} で包んで返す Provider。
 *
 * <p>MCP の ToolCallbackProvider と defaultTools で登録された Tool の両方を
 * 同じ境界でイベント発行できるようにする。</p>
 */
public class ToolEventCallbackProvider implements ToolCallbackProvider {

  private final ToolCallbackProvider delegate;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;

  public ToolEventCallbackProvider(ToolCallbackProvider delegate, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public ToolCallback[] getToolCallbacks() {
    ToolCallback[] callbacks = delegate.getToolCallbacks();
    if (callbacks == null) {
      return new ToolCallback[0];
    }
    ToolCallback[] decorated = new ToolCallback[callbacks.length];
    for (int i = 0; i < callbacks.length; i++) {
      decorated[i] = new ToolEventCallbackDecorator(callbacks[i], eventFactory, eventPublisher);
    }
    return decorated;
  }
}
