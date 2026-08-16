package dev.mikoto2000.rei.temporal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class AgentEventFactory {
  private final Clock clock;

  public AgentEventFactory(Clock clock) {
    this.clock = clock;
  }

  public AgentEvent create(String type, String subject, Map<String, Object> payload) {
    return new AgentEvent(Instant.now(clock), type, subject, payload == null ? Map.of() : Map.copyOf(payload));
  }
}
