package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標繰り返しサービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoalLoopServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う目標一覧。 */
  GoalRepository goals;

  /** 検証に使う確認の窓口。 */
  FileGoalVerifier verifier = new FileGoalVerifier();

  /** {@code digest} の条件を再現するための補助情報を返す。 */
  String digest(String text) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    goals =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  GoalRepository.Goal create(int runs, int calls) throws Exception {
    return goals.create(
        new AgentRunContext("source", "session", dir, "project"),
        "Write exact artifact",
        "out.txt",
        digest("correct"),
        runs,
        calls);
  }

  /** {@code loop} の条件を再現するための補助情報を返す。 */
  GoalLoopService loop(boolean enabled, GoalLoopService.Gateway gateway) {
    return new GoalLoopService(
        goals,
        verifier,
        gateway,
        new ToolPermissionProperties(enabled, null, null, null),
        new GoalEvents(event -> {}, Clock.systemUTC()));
  }

  /** 目標の達成管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void modelCompletionClaimCannotCompleteGoalAndOnlyVerifiedFileEndsLoop() throws Exception {
    var goal = create(3, 5);
    var calls = new AtomicInteger();
    var loop =
        loop(
            true,
            (claim, run, done) -> {
              assertTrue(goals.reserveLlm(claim));
              int attempt = calls.incrementAndGet();
              try {
                Files.writeString(dir.resolve("out.txt"), attempt == 1 ? "wrong" : "correct");
              } catch (Exception error) {
                throw new RuntimeException(error);
              }
              done.accept(
                  new GoalLoopService.Outcome(ChatExecutionResult.success("All done", false)));
            });
    var result = loop.run("project", goal.id());
    assertEquals("COMPLETED", result.status());
    assertEquals(2, result.attempts());
    assertEquals(2, result.llmCallsUsed());
    assertEquals(
        List.of("UNVERIFIED", "VERIFIED"),
        goals.attempts("project", goal.id()).stream().map(GoalRepository.Attempt::status).toList());
  }

  /** 成功済みのを停止することを確認する。 */
  @Test
  void wholeGoalBudgetStopsSuccessfulButUnverifiedRunsAndLocalRunCapStillApplies()
      throws Exception {
    var goal = create(5, 2);
    var calls = new AtomicInteger();
    var loop =
        loop(
            true,
            (claim, run, done) -> {
              var reservation =
                  new OutputLimitRunBudget.LlmCallReservation() {
                    /** {@code tryReserve} の条件を再現するための補助情報を返す。 */
                    public boolean tryReserve() {
                      return goals.reserveLlm(claim);
                    }

                    /** {@code remaining} の条件を再現するための補助情報を返す。 */
                    public int remaining() {
                      return goals.remainingLlm(claim);
                    }
                  };
              var budget = new OutputLimitRunBudget(1, 1, reservation);
              assertTrue(budget.tryConsumeLlmCall());
              assertFalse(budget.tryConsumeLlmCall());
              calls.incrementAndGet();
              done.accept(
                  new GoalLoopService.Outcome(ChatExecutionResult.success("Complete", false)));
            });
    var result = loop.run("project", goal.id());
    assertEquals("BLOCKED", result.status());
    assertEquals(2, calls.get());
    assertEquals(2, result.llmCallsUsed());
    assertThrows(IllegalStateException.class, () -> loop.run("project", goal.id()));
  }

  /** なしモデル呼び出しを必要とすることを確認する。 */
  @Test
  void preexistingEvidenceRequiresNoModelCallAndPolicyDisabledCannotDispatch() throws Exception {
    var goal = create(3, 5);
    var calls = new AtomicInteger();
    var disabled = loop(false, (claim, run, done) -> calls.incrementAndGet());
    assertThrows(IllegalStateException.class, () -> disabled.run("project", goal.id()));
    assertEquals("READY", goals.get("project", goal.id()).status());
    Files.writeString(dir.resolve("out.txt"), "correct");
    var verified = disabled.verify("project", goal.id());
    assertEquals("COMPLETED", verified.goal().status());
    assertEquals(0, verified.goal().attempts());
    assertEquals(0, calls.get());
  }

  /** 目標の達成管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void permissionFailuresAndCancellationDoNotAutomaticallyRetry() throws Exception {
    var goal = create(3, 5);
    var calls = new AtomicInteger();
    var permission =
        loop(
            true,
            (claim, run, done) -> {
              calls.incrementAndGet();
              done.accept(
                  new GoalLoopService.Outcome(
                      ChatExecutionResult.failed("approval"), "permission_required"));
            });
    assertEquals("WAITING_APPROVAL", permission.run("project", goal.id()).status());
    assertEquals(1, calls.get());
    var cancel =
        loop(
            true,
            (claim, run, done) ->
                done.accept(new GoalLoopService.Outcome(ChatExecutionResult.cancelled())));
    assertEquals("PAUSED", cancel.run("project", goal.id()).status());
    assertEquals(2, goals.get("project", goal.id()).attempts());
    var failed =
        loop(
            true,
            (claim, run, done) ->
                done.accept(new GoalLoopService.Outcome(ChatExecutionResult.failed("failure"))));
    assertEquals("FAILED", failed.run("project", goal.id()).status());
    assertEquals(3, goals.get("project", goal.id()).attempts());
  }

  /** 目標の達成管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationInvalidatesBudgetReservationsAndLateCompletion() throws Exception {
    var goal = create(3, 5);
    var callback =
        new java.util.concurrent.atomic.AtomicReference<
            java.util.function.Consumer<GoalLoopService.Outcome>>();
    var claimRef = new java.util.concurrent.atomic.AtomicReference<GoalRepository.Claim>();
    var loop =
        loop(
            true,
            (claim, run, done) -> {
              claimRef.set(claim);
              callback.set(done);
            });
    loop.run("project", goal.id());
    loop.cancel("project", goal.id());
    assertFalse(goals.reserveLlm(claimRef.get()));
    callback.get().accept(new GoalLoopService.Outcome(ChatExecutionResult.success("Done", false)));
    assertEquals("CANCELLED", goals.get("project", goal.id()).status());
    assertEquals("CANCELLED", goals.attempts("project", goal.id()).getFirst().status());
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void verifierRejectsWrongDigestDirectoriesAndOversizedFiles() throws Exception {
    var goal = create(3, 5);
    assertFalse(verifier.verify(goal).satisfied());
    Files.writeString(dir.resolve("out.txt"), "wrong");
    assertEquals("digest_mismatch", verifier.verify(goal).reason());
    Files.write(dir.resolve("out.txt"), new byte[1_048_577]);
    assertEquals("file_too_large", verifier.verify(goal).reason());
    Files.delete(dir.resolve("out.txt"));
    Files.createDirectory(dir.resolve("out.txt"));
    assertFalse(verifier.verify(goal).satisfied());
  }
}
