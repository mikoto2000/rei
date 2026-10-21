package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標再開所有権の結果と状態の契約を検証するテスト。 */
class GoalResumeOwnershipTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 目標の達成管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void oldAttemptCallbackCannotFailOrCompleteNextAttempt() throws Exception {
    var repo =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("state.db")),
            Clock.systemUTC());
    var goal =
        repo.create(
            new AgentRunContext("human", "s", root, "p"),
            "create artifact",
            "out",
            "a".repeat(64),
            3,
            4);
    var callbacks = new ArrayList<Consumer<GoalLoopService.Outcome>>();
    var loop =
        new GoalLoopService(
            repo,
            new FileGoalVerifier(),
            (claim, run, done) -> callbacks.add(done),
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null),
            new GoalEvents(e -> {}, Clock.systemUTC()));
    loop.run("p", goal.id());
    callbacks
        .getFirst()
        .accept(new GoalLoopService.Outcome(ChatExecutionResult.success("done", false)));
    String current = repo.get("p", goal.id()).currentRunId();
    assertEquals(2, callbacks.size());
    assertDoesNotThrow(
        () ->
            callbacks
                .getFirst()
                .accept(new GoalLoopService.Outcome(ChatExecutionResult.failed("late"))));
    assertEquals(current, repo.get("p", goal.id()).currentRunId());
    assertEquals("RUNNING", repo.get("p", goal.id()).status());
    assertEquals("RUNNING", repo.attempts("p", goal.id()).getLast().status());
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void oldRunReservationCannotChargeNewAttemptUnderSameGoalClaim() {
    var repo =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("state.db")),
            Clock.systemUTC());
    var goal =
        repo.create(
            new AgentRunContext("human", "s", root, "p"),
            "create artifact",
            "out",
            "a".repeat(64),
            3,
            4);
    var claim = repo.claim("p", goal.id());
    String old = repo.beginAttempt(claim);
    var budget = repo.modelBudget(claim, old);
    assertTrue(budget.tryReserve());
    repo.recordAttempt(claim, old, "UNVERIFIED", "digest_mismatch");
    String current = repo.beginAttempt(claim);
    assertFalse(budget.tryReserve());
    assertEquals(0, budget.remaining());
    assertEquals(1, repo.get("p", goal.id()).llmCallsUsed());
    assertTrue(repo.modelBudget(claim, current).tryReserve());
    assertEquals(2, repo.get("p", goal.id()).llmCallsUsed());
  }
}
