package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 実行予定復旧の結果と状態の契約を検証するテスト。 */
class ScheduleRecoveryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T03:00:00Z");

  /** {@code scheduler} の条件を再現するための補助情報を返す。 */
  PersistentAgentScheduler scheduler() {
    return new PersistentAgentScheduler(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("timers.db")),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  String create(PersistentAgentScheduler scheduler) {
    try (var scope = AgentRunScope.open(new AgentRunContext("source", "session", dir, "project"))) {
      var task = scheduler.scheduleAfter(Duration.ZERO, "inspect", "session");
      scheduler.activate("project", task.id());
      return task.id();
    }
  }

  /** 時刻と期間の解釈で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void restartReconciliationEndsUncertainClaimWithoutReplayingAndUnblocksOtherSchedule() {
    var original = scheduler();
    String first = create(original);
    var claim = original.claimDue().orElseThrow();
    String second = create(original);
    assertTrue(original.claimDue().isEmpty());
    var restarted = scheduler();
    var result = restarted.reconcile("project", first, claim.runId());
    assertEquals("FAILED", result.status());
    assertEquals("uncertain_run_reconciled", result.outcome());
    assertEquals(claim.runId(), result.runId());
    assertEquals(second, restarted.claimDue().orElseThrow().task().id());
    assertThrows(IllegalStateException.class, () -> original.finish(claim, "COMPLETED", "late"));
    assertThrows(IllegalStateException.class, () -> restarted.activate("project", first));
    assertEquals("FAILED", restarted.history("project", first).getLast().status());
  }

  /** 時刻と期間の解釈で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void wrongProjectStaleRunAndTerminalStateAreRejected() {
    var scheduler = scheduler();
    String id = create(scheduler);
    var claim = scheduler.claimDue().orElseThrow();
    assertThrows(
        IllegalArgumentException.class, () -> scheduler.reconcile("other", id, claim.runId()));
    assertThrows(IllegalStateException.class, () -> scheduler.reconcile("project", id, "stale"));
    scheduler.reconcile("project", id, claim.runId());
    assertThrows(
        IllegalStateException.class, () -> scheduler.reconcile("project", id, claim.runId()));
  }
}
