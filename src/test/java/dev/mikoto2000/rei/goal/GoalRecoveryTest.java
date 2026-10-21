package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標復旧の結果と状態の契約を検証するテスト。 */
class GoalRecoveryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  GoalRepository repository;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repository =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
  }

  /** {@code goal} の条件を再現するための補助情報を返す。 */
  GoalRepository.Goal goal() {
    return repository.create(
        new AgentRunContext("source", "session", dir, "project"),
        "artifact",
        "out.txt",
        "a".repeat(64),
        3,
        2);
  }

  /** 明示的な状態の照合結果変更後再開について、予算を保持することを確認する。 */
  @Test
  void explicitReconciliationAfterRestartPreservesBudgetAndRevokesOldClaim() {
    var goal = goal();
    var claim = repository.claim("project", goal.id());
    String run = repository.beginAttempt(claim);
    assertTrue(repository.reserveLlm(claim));
    var restarted =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
    var recovered = restarted.reconcile("project", goal.id(), run);
    assertEquals("PAUSED", recovered.status());
    assertEquals(1, recovered.attempts());
    assertEquals(1, recovered.llmCallsUsed());
    assertEquals("BLOCKED", restarted.attempts("project", goal.id()).getFirst().status());
    assertFalse(repository.reserveLlm(claim));
    assertThrows(
        IllegalStateException.class,
        () -> repository.stop(claim, "COMPLETED", "file_digest_verified"));
    var resumed = restarted.claim("project", goal.id());
    assertNotEquals(claim.token(), resumed.token());
    assertTrue(restarted.reserveLlm(resumed));
    assertFalse(restarted.reserveLlm(resumed));
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleRunAndWrongProjectCannotReconcile() {
    var goal = goal();
    var claim = repository.claim("project", goal.id());
    String run = repository.beginAttempt(claim);
    assertThrows(
        IllegalStateException.class, () -> repository.reconcile("project", goal.id(), "stale"));
    assertThrows(
        IllegalArgumentException.class, () -> repository.reconcile("other", goal.id(), run));
    assertTrue(repository.active(claim));
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void claimWithoutAttemptCanBeReconciledButTerminalGoalCannot() {
    var goal = goal();
    repository.claim("project", goal.id());
    assertEquals("PAUSED", repository.reconcile("project", goal.id(), null).status());
    assertThrows(
        IllegalStateException.class, () -> repository.reconcile("project", goal.id(), null));
  }
}
