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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoalRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  GoalRepository repository;

  /** 検証に使う内容の照合値。 */
  String digest = "a".repeat(64);

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("source", "session", dir, "project");
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repository =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
  }

  /** 予算について、再開の後も対象の情報が残ることを確認する。 */
  @Test
  void budgetSurvivesRestartAndExplicitResumeNeverReplenishesIt() {
    var goal = repository.create(owner(), "Produce artifact", "out.txt", digest, 3, 2);
    var claim = repository.claim("project", goal.id());
    var run = repository.beginAttempt(claim);
    assertTrue(repository.reserveLlm(claim));
    assertTrue(repository.reserveLlm(claim));
    assertFalse(repository.reserveLlm(claim));
    repository.recordAttempt(claim, run, "UNVERIFIED", "digest_mismatch");
    repository.stop(claim, "BLOCKED", "llm_budget_exhausted");
    var restarted =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
    assertEquals(2, restarted.get("project", goal.id()).llmCallsUsed());
    assertEquals(1, restarted.get("project", goal.id()).attempts());
    assertThrows(IllegalStateException.class, () -> restarted.claim("project", goal.id()));
  }

  /** 目標の達成管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void projectBoundaryAndUncertainRunningClaimArePreserved() {
    var goal = repository.create(owner(), "Produce artifact", "out.txt", digest, 3, 5);
    var claim = repository.claim("project", goal.id());
    assertThrows(IllegalArgumentException.class, () -> repository.get("other", goal.id()));
    assertThrows(IllegalStateException.class, () -> repository.claim("project", goal.id()));
    var other =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
    assertThrows(IllegalStateException.class, () -> other.claim("project", goal.id()));
    repository.stop(claim, "WAITING_APPROVAL", "permission_required");
    var resumed = other.claim("project", goal.id());
    assertNotEquals(claim.token(), resumed.token());
    assertFalse(repository.reserveLlm(claim));
    assertTrue(other.reserveLlm(resumed));
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void unsafeCriteriaAndUnboundedRunsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.create(owner(), "x", "../secret", digest, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.create(owner(), "x", dir.resolve("out").toString(), digest, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.create(owner(), "x", "out", "invalid", 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.create(owner(), "x", "out", digest, 11, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.create(owner(), "x", "out", digest, 1, 101));
  }

  /** 目標の達成管理で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentReservationsCannotOverspendAndAnotherGoalCannotOwnTheSession() throws Exception {
    var goal = repository.create(owner(), "Artifact", "out.txt", digest, 3, 1);
    var claim = repository.claim("project", goal.id());
    var second = repository.create(owner(), "Another artifact", "other.txt", digest, 3, 2);
    assertThrows(RuntimeException.class, () -> repository.claim("project", second.id()));
    var reopened =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> repository.reserveLlm(claim));
      var b = pool.submit(() -> reopened.reserveLlm(claim));
      assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
    }
    assertEquals(1, repository.get("project", goal.id()).llmCallsUsed());
  }
}
