package dev.mikoto2000.rei.event;

/**
 * Agent Event を購読する Listener。
 */
@FunctionalInterface
public interface AgentEventListener {
  void onEvent(AgentEvent event);
}
