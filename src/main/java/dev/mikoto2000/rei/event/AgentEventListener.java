package dev.mikoto2000.rei.event;

/** Agent Event を購読する Listener。 */
@FunctionalInterface
public interface AgentEventListener {
  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  void onEvent(AgentEvent event);
}
