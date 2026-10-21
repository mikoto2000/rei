package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 実行予定の管理ツール一覧の結果と状態の契約を検証するテスト。 */
class SchedulerToolsTest {
  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationIsExposedAsToolAndRemovesOnlyTheSelectedTimer() throws Exception {
    var tools = new SchedulerTools(new InMemoryAgentScheduler(Clock.systemUTC()));
    var first = tools.scheduleAfter("10m", "first", "chat");
    var second = tools.scheduleAfter("20m", "second", "chat");
    assertEquals("Schedule cancelled", tools.cancelScheduledAction(first.id()));
    assertEquals(java.util.List.of(second), tools.listScheduledActions());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> tools.cancelScheduledAction(first.id()));
    assertEquals(
        "cancelScheduledAction",
        SchedulerTools.class
            .getMethod("cancelScheduledAction", String.class)
            .getAnnotation(org.springframework.ai.tool.annotation.Tool.class)
            .name());
    assertEquals(
        java.util.Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.LOCAL_WRITE),
        dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicCapabilities(
            "cancelScheduledAction"));
  }

  /** 時刻と期間の解釈で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void scheduleAfterParsesHumanDurationWithoutSleeping() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    SchedulerTools tools = new SchedulerTools(new InMemoryAgentScheduler(fixed));

    ScheduledAgentTask actual = tools.scheduleAfter("10m", "check_server_status", "chat-1");

    assertEquals(Instant.parse("2026-08-16T16:45:00Z"), actual.executeAt());
    assertEquals("check_server_status", actual.action());
  }

  /** 時刻と期間の解釈で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void scheduleAtParsesOffsetDateTime() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:00Z"), ZoneId.of("Asia/Tokyo"));
    SchedulerTools tools = new SchedulerTools(new InMemoryAgentScheduler(fixed));

    ScheduledAgentTask actual =
        tools.scheduleAt("2026-08-17T01:45:00+09:00", "check_server_status", "chat-1");

    assertEquals(Instant.parse("2026-08-16T16:45:00Z"), actual.executeAt());
  }
}
