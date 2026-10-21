package dev.mikoto2000.rei.event;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * ToolCallbackProvider を Decorator でラップし、各 ToolCallback を {@link ToolEventCallbackDecorator} で包んで返す
 * Provider。
 *
 * <p>MCP の ToolCallbackProvider と defaultTools で登録された Tool の両方を 同じ境界でイベント発行できるようにする。
 */
public class ToolEventCallbackProvider implements ToolCallbackProvider {

  /** 委譲先。 */
  private final ToolCallbackProvider delegate;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 指定された値や利用先を使って、ToolEventCallbackProviderを初期化する。 */
  public ToolEventCallbackProvider(
      ToolCallbackProvider delegate,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 実行状況の通知で参照するツール完了時の処理一覧を返す。 */
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
