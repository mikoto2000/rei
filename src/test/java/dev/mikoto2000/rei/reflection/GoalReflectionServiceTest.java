package dev.mikoto2000.rei.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.goal.GoalEvents;
import dev.mikoto2000.rei.goal.GoalRepository;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 目標振り返りサービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoalReflectionServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う目標一覧。 */
  GoalRepository goals;

  /** 検証に使う振り返り一覧。 */
  GoalReflectionRepository reflections;

  /** 検証に使うサービス。 */
  GoalReflectionService service;

  /** 検証に使う通知の連携先。 */
  InMemoryAgentEventBus bus;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.systemUTC();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("reflection.db"));
    goals = new GoalRepository(source, clock);
    reflections = new GoalReflectionRepository(source, clock);
    bus = new InMemoryAgentEventBus();
    service = new GoalReflectionService(goals, reflections, bus, clock);
    service.start();
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    service.close();
  }

  /** {@code goal} の条件を再現するための補助情報を返す。 */
  GoalRepository.Goal goal() {
    return goals.create(
        new AgentRunContext("source", "session", dir, "project"),
        "secret objective",
        "out.txt",
        "a".repeat(64),
        3,
        5);
  }

  /** 作業の振り返りで、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void recordsExpectedAndVerifiedDifferenceWithoutTreatingFailureAsAValidatedLesson() {
    var goal = goal();
    var claim = goals.claim("project", goal.id());
    String run = goals.beginAttempt(claim);
    goals.recordAttempt(claim, run, "UNVERIFIED", "digest_mismatch");
    var stopped = goals.stop(claim, "BLOCKED", "budget_exhausted");
    var events = new GoalEvents(bus, clock);
    events.publish(stopped);
    events.publish(stopped);
    var item = reflections.list("project").getFirst();
    assertEquals("UNVERIFIED", item.actual());
    assertEquals("CRITERION_NOT_SATISFIED", item.gap());
    assertEquals("REVIEW_GOAL_BUDGET", item.nextAction());
    assertEquals("out.txt", item.expectedFile());
    assertEquals(1, reflections.list("project").size());
    assertFalse(item.toString().contains("secret objective"));
    var restarted =
        new GoalReflectionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("reflection.db")), clock);
    assertEquals(item.id(), restarted.list("project").getFirst().id());
    assertThrows(IllegalArgumentException.class, () -> restarted.get("other", item.id()));
  }

  /** 作業の振り返りの操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void approvalAndUnknownExecutionDoNotFabricateVerificationEvidence() {
    var goal = goal();
    var claim = goals.claim("project", goal.id());
    String run = goals.beginAttempt(claim);
    goals.recordAttempt(claim, run, "WAITING_APPROVAL", "execution_stopped");
    var stopped = goals.stop(claim, "WAITING_APPROVAL", "execution_stopped");
    var item = service.collect("project", goal.id());
    assertEquals("NOT_CHECKED", item.actual());
    assertEquals("VERIFICATION_NOT_RECORDED", item.gap());
    assertEquals("REVIEW_APPROVAL", item.nextAction());
    assertEquals(item.id(), service.collect("project", goal.id()).id());
    new GoalEvents(bus, clock).publish(stopped);
    assertEquals(1, reflections.list("project").size());
  }

  /** 作業の振り返りで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void completedCriterionIsRecordedAsEvidenceAndRunningGoalsCannotBeCollected() {
    var goal = goal();
    assertThrows(IllegalStateException.class, () -> service.collect("project", goal.id()));
    goals.verifiedWithoutRun("project", goal.id());
    var item = service.collect("project", goal.id());
    assertEquals("VERIFIED", item.actual());
    assertEquals("CRITERION_SATISFIED", item.gap());
    assertEquals("REVIEW_VERIFIED_RESULT", item.nextAction());
  }

  /** 作業の振り返りで、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void lateEventUsesItsOwnAttemptInsteadOfTheLatestGoalResult() {
    var goal = goal();
    var first = goals.claim("project", goal.id());
    String run = goals.beginAttempt(first);
    goals.recordAttempt(first, run, "WAITING_APPROVAL", "execution_stopped");
    var waiting = goals.stop(first, "WAITING_APPROVAL", "execution_stopped");
    var second = goals.claim("project", goal.id());
    String next = goals.beginAttempt(second);
    goals.recordAttempt(second, next, "VERIFIED", "file_digest_verified");
    goals.stop(second, "COMPLETED", "file_digest_verified");
    new GoalEvents(bus, clock).publish(waiting);
    var item = reflections.list("project").getFirst();
    assertEquals(run, item.runId());
    assertEquals("NOT_CHECKED", item.actual());
    assertEquals("REVIEW_APPROVAL", item.nextAction());
  }

  /** 作業の振り返りで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void shellShowsScopedFactsAndCollectDoesNotExecuteOrVerifyTheGoalAgain() {
    var goal = goal();
    goals.verifiedWithoutRun("project", goal.id());
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var project = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);
    org.mockito.Mockito.when(project.id()).thenReturn("project");
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);
    var command = new ReflectionCommand(reflections, service, projects);
    var output = new java.io.StringWriter();
    command.setShellOutput(new java.io.PrintWriter(output));
    var cli = new picocli.CommandLine(command);
    assertEquals(0, cli.execute("collect", goal.id()));
    var item = reflections.list("project").getFirst();
    assertEquals(0, cli.execute("show", item.id()));
    assertTrue(output.toString().contains("CRITERION_SATISFIED"));
    assertEquals(0, goals.get("project", goal.id()).attempts());
    org.mockito.Mockito.when(project.id()).thenReturn("other");
    assertEquals(2, cli.execute("show", item.id()));
  }
}
