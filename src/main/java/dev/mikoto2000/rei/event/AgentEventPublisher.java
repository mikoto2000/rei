package dev.mikoto2000.rei.event;

/**
 * Agent Event を発行する Publisher。
 */
public interface AgentEventPublisher {
  void publish(AgentEvent event);
  /** Durability-sensitive boundary: failures must reach the caller before a side effect. */
  default void publishBoundary(AgentEvent event) { publish(event); }
}
