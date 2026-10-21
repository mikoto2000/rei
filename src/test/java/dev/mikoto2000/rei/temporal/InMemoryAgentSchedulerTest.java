package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 入力先記憶エージェント実行予定の管理の結果と状態の契約を検証するテスト。 */
class InMemoryAgentSchedulerTest {

  /** 時刻と期間の解釈で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void scheduleAfterCalculatesExecuteAtFromClock() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    AgentScheduler scheduler = new InMemoryAgentScheduler(fixed);

    ScheduledAgentTask actual =
        scheduler.scheduleAfter(Duration.ofMinutes(10), "check_server_status", "chat-1");

    assertEquals(Instant.parse("2026-08-16T16:35:00Z"), actual.createdAt());
    assertEquals(Instant.parse("2026-08-16T16:45:00Z"), actual.executeAt());
    assertEquals("check_server_status", actual.action());
    assertEquals("chat-1", actual.conversationId());
  }

  /** 実行予定日時について、要求された実行を維持することを確認する。 */
  @Test
  void scheduleAtKeepsRequestedExecuteAt() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    AgentScheduler scheduler = new InMemoryAgentScheduler(fixed);
    Instant executeAt = Instant.parse("2026-08-16T16:45:00Z");

    ScheduledAgentTask actual = scheduler.scheduleAt(executeAt, "check_server_status", "chat-1");

    assertEquals(executeAt, actual.executeAt());
  }
}
