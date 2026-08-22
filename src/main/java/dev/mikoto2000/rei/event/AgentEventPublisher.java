package dev.mikoto2000.rei.event;

/**
 * Agent Event を発行する Publisher。
 */
public interface AgentEventPublisher {
  void publish(AgentEvent event);
}
