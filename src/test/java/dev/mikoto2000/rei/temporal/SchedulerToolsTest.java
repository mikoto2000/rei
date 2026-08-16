package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class SchedulerToolsTest {

  @Test
  void scheduleAfterParsesHumanDurationWithoutSleeping() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    SchedulerTools tools = new SchedulerTools(new InMemoryAgentScheduler(fixed));

    ScheduledAgentTask actual = tools.scheduleAfter("10m", "check_server_status", "chat-1");

    assertEquals(Instant.parse("2026-08-16T16:45:00Z"), actual.executeAt());
    assertEquals("check_server_status", actual.action());
  }

  @Test
  void scheduleAtParsesOffsetDateTime() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    SchedulerTools tools = new SchedulerTools(new InMemoryAgentScheduler(fixed));

    ScheduledAgentTask actual = tools.scheduleAt("2026-08-17T01:45:00+09:00", "check_server_status", "chat-1");

    assertEquals(Instant.parse("2026-08-16T16:45:00Z"), actual.executeAt());
  }
}
