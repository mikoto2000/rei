package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class InMemoryAgentSchedulerTest {

  @Test
  void scheduleAfterCalculatesExecuteAtFromClock() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    AgentScheduler scheduler = new InMemoryAgentScheduler(fixed);

    ScheduledAgentTask actual = scheduler.scheduleAfter(Duration.ofMinutes(10), "check_server_status", "chat-1");

    assertEquals(Instant.parse("2026-08-16T16:35:00Z"), actual.createdAt());
    assertEquals(Instant.parse("2026-08-16T16:45:00Z"), actual.executeAt());
    assertEquals("check_server_status", actual.action());
    assertEquals("chat-1", actual.conversationId());
  }

  @Test
  void scheduleAtKeepsRequestedExecuteAt() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    AgentScheduler scheduler = new InMemoryAgentScheduler(fixed);
    Instant executeAt = Instant.parse("2026-08-16T16:45:00Z");

    ScheduledAgentTask actual = scheduler.scheduleAt(executeAt, "check_server_status", "chat-1");

    assertEquals(executeAt, actual.executeAt());
  }
}
