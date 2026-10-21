package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 定期実行条件実行予定の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class CronScheduleTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T00:00:00Z");

  /** {@code scheduler} の条件を再現するための補助情報を返す。 */
  PersistentAgentScheduler scheduler(Instant time) {
    return new PersistentAgentScheduler(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("cron.db")),
        Clock.fixed(time, ZoneOffset.UTC));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  String create(PersistentAgentScheduler scheduler, String cron, String zone, int occurrences) {
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", "session", dir, "project"))) {
      return scheduler.scheduleCron(cron, zone, occurrences, "Check project", "session").id();
    }
  }

  /** 時刻と期間の解釈で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void timezonePersistenceCoalescingAndBoundedCompletion() {
    var first = scheduler(now);
    String id = create(first, "0 0 10 * * *", "Asia/Tokyo", 2);
    assertEquals(now.plusSeconds(3600), first.get("project", id).task().executeAt());
    assertTrue(first.claimDue().isEmpty());
    first.activate("project", id);
    var restarted = scheduler(Instant.parse("2026-10-07T02:00:00Z"));
    var claim = restarted.claimDue().orElseThrow();
    assertTrue(restarted.claimDue().isEmpty());
    restarted.finish(claim, "COMPLETED", "ok");
    assertEquals(
        Instant.parse("2026-10-08T01:00:00Z"), restarted.get("project", id).task().executeAt());
    assertEquals(1, restarted.cron("project", id).orElseThrow().remaining());
    assertThrows(IllegalStateException.class, () -> restarted.finish(claim, "COMPLETED", "stale"));
    var last = scheduler(Instant.parse("2026-10-08T01:00:00Z"));
    last.finish(last.claimDue().orElseThrow(), "COMPLETED", "ok");
    assertEquals("COMPLETED", last.get("project", id).status());
    assertEquals(0, last.cron("project", id).orElseThrow().remaining());
    assertTrue(last.claimDue().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> last.cron("other", id));
  }

  /** 時刻と期間の解釈で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidConfigurationLeavesNoPartialSchedule() {
    var scheduler = scheduler(now);
    for (String cron : new String[] {"bad", "* * * * * *", "0 0 0 31 2 *"})
      assertThrows(IllegalArgumentException.class, () -> create(scheduler, cron, "UTC", 2));
    assertThrows(
        IllegalArgumentException.class, () -> create(scheduler, "0 * * * * *", "Invalid/Zone", 2));
    assertThrows(
        IllegalArgumentException.class, () -> create(scheduler, "0 * * * * *", "UTC", 101));
    assertTrue(scheduler.list("project").isEmpty());
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void uncertainFailureAndCancellationNeverAutomaticallyRepeat() {
    var first = scheduler(now);
    String id = create(first, "0 * * * * *", "UTC", 3);
    first.activate("project", id);
    var due = scheduler(now.plusSeconds(60));
    var claim = due.claimDue().orElseThrow();
    var restarted = scheduler(now.plusSeconds(600));
    assertTrue(restarted.claimDue().isEmpty());
    restarted.reconcile("project", id, claim.runId());
    assertTrue(restarted.claimDue().isEmpty());
    String failed = create(restarted, "0 * * * * *", "UTC", 3);
    restarted.activate("project", failed);
    var later = scheduler(now.plusSeconds(660));
    later.finish(later.claimDue().orElseThrow(), "FAILED", "failed");
    assertTrue(scheduler(now.plusSeconds(1200)).claimDue().isEmpty());
    String cancelled = create(later, "0 * * * * *", "UTC", 3);
    later.activate("project", cancelled);
    later.cancel("project", cancelled);
    assertTrue(scheduler(now.plusSeconds(1200)).claimDue().isEmpty());
  }

  /** 時刻と期間の解釈で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void localWallClockHandlesSpringDstGap() {
    var scheduler = scheduler(Instant.parse("2026-03-07T08:00:00Z"));
    String id = create(scheduler, "0 30 2 * * *", "America/New_York", 2);
    assertEquals(
        Instant.parse("2026-03-09T06:30:00Z"), scheduler.get("project", id).task().executeAt());
  }

  /** 完全一致の会話所有権を維持することを確認する。 */
  @Test
  void toolRegistrationKeepsExactSessionOwnershipAndPendingState() {
    var scheduler = scheduler(now);
    var tools = new SchedulerTools(scheduler);
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", "session", dir, "project"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> tools.scheduleCron("0 * * * * *", "UTC", 2, "Check", "other"));
      var task = tools.scheduleCron("0 * * * * *", "UTC", 2, "Check", "session");
      assertEquals("PENDING", scheduler.get("project", task.id()).status());
      assertTrue(scheduler.claimDue().isEmpty());
    }
  }
}
