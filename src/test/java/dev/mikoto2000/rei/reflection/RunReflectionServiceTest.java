package dev.mikoto2000.rei.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 実行振り返りサービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class RunReflectionServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

  /** 検証に使うリポジトリ。 */
  RunReflectionRepository repo;

  /** 検証に使うサービス。 */
  RunReflectionService service;

  /** 検証に使う通知の連携先。 */
  InMemoryAgentEventBus bus;

  /** 通知された出来事と順序を検証するための記録。 */
  AgentEventFactory events;

  /** 検証に使う所有者。 */
  AgentRunContext owner = new AgentRunContext("run", "session", Path.of("."), "project");

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repo =
        new RunReflectionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("reflection.db")), clock);
    bus = new InMemoryAgentEventBus();
    events = new AgentEventFactory(clock);
    service = new RunReflectionService(repo, bus);
    service.start();
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    service.close();
  }

  /** 作業の振り返りで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void completedRunRecordsActualToolFactsAndDoesNotInventTaskVerification() {
    var tool =
        events
            .toolCompleted("call", "readFile", 1L, "token=private", null, 10L, null, null)
            .withOwnership(owner);
    bus.publish(tool);
    bus.publish(tool);
    bus.publish(events.runCompleted("run", 1).withOwnership(owner));
    var item = repo.list("project").getFirst();
    assertEquals("RUN", item.sourceKind());
    assertEquals("COMPLETED", item.status());
    assertEquals(1, item.actions().size());
    assertEquals("readFile", item.actions().getFirst().tool());
    assertEquals("TASK_CRITERIA_NOT_DECLARED", item.gap());
    assertFalse(item.toString().contains("private"));
    assertEquals("INSPECT_RESULT_AND_VERIFY_CRITERIA", item.nextAction());
  }

  /** 作業の振り返りで、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void failuresAreStructuredRedactedDurableAndDeduplicated() {
    bus.publish(
        events
            .toolFailed(
                "call",
                "writeFile",
                new ErrorInformation("PermissionRequired", "token=private", null))
            .withOwnership(owner));
    var terminal =
        events
            .runFailed("run", new ErrorInformation("ExecutionFailure", "token=private", null))
            .withOwnership(owner);
    bus.publish(terminal);
    bus.publish(terminal);
    var item = repo.list("project").getFirst();
    assertEquals("ExecutionFailure", item.failureReason());
    assertEquals("REVIEW_APPROVAL_BEFORE_RETRY", item.nextAction());
    assertFalse(item.knowledgeCandidates().isEmpty());
    assertFalse(item.toString().contains("private"));
    var restored =
        new RunReflectionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("reflection.db")), clock);
    assertEquals(item, restored.get("project", item.id()));
    assertEquals(1, restored.list("project").size());
    assertThrows(IllegalArgumentException.class, () -> restored.get("other", item.id()));
  }

  /** 作業の振り返りで、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void taskCompletionAndFailureAreIndependentFromParentRun() {
    bus.publish(events.taskCreated("task", null, "Expected task", "OPEN").withOwnership(owner));
    var done = events.taskCompleted("task", 1L).withOwnership(owner);
    bus.publish(done);
    bus.publish(done);
    bus.publish(
        events
            .taskFailed("other-task", new ErrorInformation("TaskFailure", "private", null))
            .withOwnership(owner));
    assertEquals(2, repo.list("project").size());
    var item =
        repo.list("project").stream()
            .filter(i -> i.sourceId().equals("task"))
            .findFirst()
            .orElseThrow();
    assertEquals("TASK", item.sourceKind());
    assertEquals("Expected task", item.expected());
    assertEquals("TASK_COMPLETION_REPORTED", item.gap());
  }

  /** 作業の振り返りで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unownedOrMismatchedRunEventsCannotCreateReflections() {
    bus.publish(events.runCompleted("run", 1));
    var event = events.runCompleted("wrong", 1);
    bus.publish(
        new AgentEvent(
            event.id(),
            0,
            event.timestamp(),
            event.type(),
            1,
            "session",
            null,
            "run",
            null,
            null,
            event.payload(),
            "project"));
    assertTrue(repo.list("project").isEmpty());
  }

  /** 作業の振り返りで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellExposesRunFactsWithinCurrentProject() {
    bus.publish(events.runCompleted("run", 1).withOwnership(owner));
    var item = repo.list("project").getFirst();
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var context = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);
    org.mockito.Mockito.when(context.id()).thenReturn("project");
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(context);
    var command = new ReflectionCommand(null, null, projects);
    command.setRunReflections(repo);
    var output = new java.io.StringWriter();
    command.setShellOutput(new java.io.PrintWriter(output));
    var cli = new picocli.CommandLine(command);
    assertEquals(0, cli.execute("runs"));
    assertEquals(0, cli.execute("run", item.id()));
    assertTrue(output.toString().contains("TASK_CRITERIA_NOT_DECLARED"));
    org.mockito.Mockito.when(context.id()).thenReturn("other");
    assertEquals(2, cli.execute("run", item.id()));
  }

  /** 作業の振り返りで、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void actionFactsSurviveRestartAndOverflowIsExplicit() {
    for (int i = 0; i < 258; i++)
      bus.publish(
          events
              .toolCompleted("call-" + i, "readFile", 1, "", null, null, null, null)
              .withOwnership(owner));
    service.close();
    var restored =
        new RunReflectionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("reflection.db")), clock);
    service = new RunReflectionService(restored, bus);
    service.start();
    bus.publish(events.runCompleted("run", 1).withOwnership(owner));
    var item = restored.list("project").getFirst();
    assertEquals(256, item.actions().size());
    assertTrue(item.actionsTruncated());
    assertTrue(item.knowledgeCandidates().stream().anyMatch(k -> k.contains("256")));
  }

  /** 作業の振り返りで、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void missedTerminalCanBeCollectedFromPersistedOwnedEventsWithoutExecution() {
    String project = UUID.randomUUID().toString();
    var owned = new AgentRunContext("saved-run", "saved-session", dir, project);
    var history = new ProjectAgentEventStore(dir.resolve("history"));
    history.append(
        events.toolCompleted("saved-call", "readFile", 1, "private").withOwnership(owned));
    history.append(events.runCompleted("saved-run", 1).withOwnership(owned));
    var collector = new RunReflectionService(repo, bus, history);
    var item = collector.collect(project, "saved-session", "saved-run");
    assertEquals(1, item.actions().size());
    assertEquals(item.id(), collector.collect(project, "saved-session", "saved-run").id());
    assertThrows(
        IllegalArgumentException.class,
        () -> collector.collect(project, "other-session", "saved-run"));
    assertThrows(
        IllegalArgumentException.class,
        () -> collector.collect(project, "saved-session", "unknown"));
    assertFalse(item.toString().contains("private"));
  }
}
