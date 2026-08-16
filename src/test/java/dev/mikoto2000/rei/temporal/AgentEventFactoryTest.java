package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentEventFactoryTest {

  @Test
  void createsEventWithTimestampFromClock() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));
    AgentEventFactory factory = new AgentEventFactory(fixed);

    AgentEvent actual = factory.create("process_started", "proc-1", Map.of("pid", 12345));

    assertEquals(Instant.parse("2026-08-16T16:30:20Z"), actual.timestamp());
    assertEquals("process_started", actual.type());
    assertEquals("proc-1", actual.subject());
    assertEquals(12345, actual.payload().get("pid"));
  }
}
