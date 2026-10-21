package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 保存されるエージェント実行予定の管理の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class PersistentAgentSchedulerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T03:00:00Z");

  /** {@code scheduler} の条件を再現するための補助情報を返す。 */
  PersistentAgentScheduler scheduler(Instant instant) {
    return new PersistentAgentScheduler(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("timers.db")),
        Clock.fixed(instant, ZoneOffset.UTC));
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("source-run", "session", dir, "project");
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  String create(PersistentAgentScheduler scheduler) {
    try (var scope = AgentRunScope.open(owner())) {
      return scheduler.scheduleAfter(Duration.ofSeconds(1), "Check build status", "session").id();
    }
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void toolCancellationPersistsAndPreventsDispatch() {
    var scheduler = scheduler(now);
    var tools = new SchedulerTools(scheduler);
    for (boolean active : new boolean[] {false, true}) {
      String id = create(scheduler);
      if (active) scheduler.activate("project", id);
      try (var scope = AgentRunScope.open(owner())) {
        assertEquals("Schedule cancelled", tools.cancelScheduledAction(id));
        assertTrue(tools.listScheduledActions().isEmpty());
      }
      var restarted = scheduler(now.plusSeconds(2));
      assertEquals("CANCELLED", restarted.get("project", id).status());
      assertEquals("CANCELLED", restarted.history("project", id).getLast().status());
      assertTrue(restarted.claimDue().isEmpty());
    }
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void toolCancellationRejectsForeignOwnersAndRunningSchedules() {
    var scheduler = scheduler(now);
    var tools = new SchedulerTools(scheduler);
    String id = create(scheduler);
    assertThrows(IllegalArgumentException.class, () -> tools.cancelScheduledAction(id));
    for (var foreign :
        new AgentRunContext[] {
          new AgentRunContext("run", "session", dir, "other"),
          new AgentRunContext("run", "other", dir, "project"),
          new AgentRunContext("run", "session", dir.resolve("other"), "project")
        }) {
      try (var scope = AgentRunScope.open(foreign)) {
        assertThrows(IllegalArgumentException.class, () -> tools.cancelScheduledAction(id));
      }
    }
    assertEquals("PENDING", scheduler.get("project", id).status());
    scheduler.activate("project", id);
    var due = scheduler(now.plusSeconds(2));
    due.claimDue().orElseThrow();
    try (var scope = AgentRunScope.open(owner())) {
      assertThrows(
          IllegalStateException.class, () -> new SchedulerTools(due).cancelScheduledAction(id));
    }
    assertEquals("RUNNING", due.get("project", id).status());
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void toolCancellationStopsRecurringAndEventSchedules() {
    var scheduler = scheduler(now);
    var tools = new SchedulerTools(scheduler);
    try (var scope = AgentRunScope.open(owner())) {
      var interval = tools.scheduleInterval("1m", 3, "Repeat", "session");
      scheduler.activate("project", interval.id());
      tools.cancelScheduledAction(interval.id());
      var event =
          tools.scheduleOnEvent("source-run", "AGENT_RUN_COMPLETED", "1h", "Continue", "session");
      scheduler.activate("project", event.id());
      tools.cancelScheduledAction(event.id());
      assertEquals("CANCELLED", scheduler.get("project", event.id()).status());
      assertTrue(tools.listScheduledActions().isEmpty());
      assertTrue(scheduler(now.plusSeconds(120)).claimDue().isEmpty());
    }
  }

  /** 再開の後も対象の情報が残ることを確認する。 */
  @Test
  void pendingRequiresHumanActivationAndSurvivesRestart() {
    var first = scheduler(now);
    String id = create(first);
    var restarted = scheduler(now.plusSeconds(2));
    assertEquals("PENDING", restarted.get("project", id).status());
    assertTrue(restarted.claimDue().isEmpty());
    restarted.activate("project", id);
    var claim = restarted.claimDue().orElseThrow();
    assertEquals(dir.toAbsolutePath().toString(), claim.projectRoot());
    assertEquals("session", claim.task().conversationId());
    assertNotEquals("source-run", claim.runId());
    assertTrue(first.claimDue().isEmpty());
    restarted.finish(claim, "COMPLETED", "Done");
    assertEquals("COMPLETED", first.get("project", id).status());
    assertEquals(4, first.history("project", id).size());
  }

  /** 時刻と期間の解釈で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void exactOwnerValidationAndProjectIsolation() {
    var scheduler = scheduler(now);
    assertThrows(
        IllegalArgumentException.class,
        () -> scheduler.scheduleAfter(Duration.ofSeconds(1), "x", "session"));
    try (var scope = AgentRunScope.open(owner())) {
      assertThrows(
          IllegalArgumentException.class,
          () -> scheduler.scheduleAfter(Duration.ofSeconds(1), "x", "other"));
      assertThrows(
          IllegalArgumentException.class,
          () -> scheduler.scheduleAfter(Duration.ofSeconds(-1), "x", "session"));
      assertThrows(
          IllegalArgumentException.class,
          () -> scheduler.scheduleAt(now.minusSeconds(1), "x", "session"));
    }
    String id = create(scheduler);
    assertThrows(IllegalArgumentException.class, () -> scheduler.activate("other", id));
    scheduler.cancel("project", id);
    assertThrows(IllegalStateException.class, () -> scheduler.activate("project", id));
    assertTrue(scheduler.claimDue().isEmpty());
  }

  /** 時刻と期間の解釈で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void concurrentClaimsAndRestartNeverRepeatAnUncertainRun() throws Exception {
    var first = scheduler(now);
    String id = create(first);
    first.activate("project", id);
    var one = scheduler(now.plusSeconds(2));
    var two = scheduler(now.plusSeconds(2));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(one::claimDue);
      var b = pool.submit(two::claimDue);
      assertEquals(1, (a.get().isPresent() ? 1 : 0) + (b.get().isPresent() ? 1 : 0));
    }
    var later = scheduler(now.plusSeconds(86400));
    assertEquals("RUNNING", later.get("project", id).status());
    assertTrue(later.claimDue().isEmpty());
  }

  /** 時刻と期間の解釈で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void intervalPersistsCoalescesAndStopsAtBound() {
    var first = scheduler(now);
    String id;
    try (var scope = AgentRunScope.open(owner())) {
      id = first.scheduleInterval(Duration.ofMinutes(1), 3, "Check", "session").id();
    }
    first.activate("project", id);
    var late = scheduler(now.plusSeconds(600));
    var claim = late.claimDue().orElseThrow();
    assertTrue(late.claimDue().isEmpty());
    late.finish(claim, "COMPLETED", "one");
    assertEquals("SCHEDULED", late.get("project", id).status());
    assertEquals(2, late.interval("project", id).orElseThrow().remaining());
    assertThrows(IllegalArgumentException.class, () -> late.interval("other", id));
    assertEquals(now.plusSeconds(660), late.get("project", id).task().executeAt());
    assertThrows(IllegalStateException.class, () -> late.finish(claim, "COMPLETED", "stale"));
    assertTrue(late.claimDue().isEmpty());
    var next = scheduler(now.plusSeconds(660));
    next.finish(next.claimDue().orElseThrow(), "COMPLETED", "two");
    var last = scheduler(now.plusSeconds(720));
    last.finish(last.claimDue().orElseThrow(), "COMPLETED", "three");
    assertEquals("COMPLETED", last.get("project", id).status());
    assertEquals(0, last.interval("project", id).orElseThrow().remaining());
    assertTrue(last.claimDue().isEmpty());
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void intervalFailureAndCancellationDoNotRepeat() {
    var first = scheduler(now);
    String id;
    try (var scope = AgentRunScope.open(owner())) {
      assertThrows(
          IllegalArgumentException.class,
          () -> first.scheduleInterval(Duration.ofSeconds(1), 3, "x", "session"));
      assertThrows(
          IllegalArgumentException.class,
          () -> first.scheduleInterval(Duration.ofMinutes(1), 101, "x", "session"));
      id = first.scheduleInterval(Duration.ofMinutes(1), 3, "x", "session").id();
    }
    first.activate("project", id);
    var later = scheduler(now.plusSeconds(60));
    later.finish(later.claimDue().orElseThrow(), "FAILED", "failure");
    assertEquals("FAILED", later.get("project", id).status());
    assertTrue(scheduler(now.plusSeconds(1000)).claimDue().isEmpty());
    try (var scope = AgentRunScope.open(owner())) {
      id = first.scheduleInterval(Duration.ofMinutes(1), 3, "x", "session").id();
    }
    first.activate("project", id);
    first.cancel("project", id);
    assertTrue(later.claimDue().isEmpty());
  }
}
