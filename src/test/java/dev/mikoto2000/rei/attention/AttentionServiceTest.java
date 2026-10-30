package dev.mikoto2000.rei.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.DependencyStatusPayload;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 注意サービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class AttentionServiceTest {
  @Test
  void globalSecretaryBriefingPublishesOneProfileEventAndKeepsAnIdempotentLocalReceipt() {
    var events = new java.util.ArrayList<AgentEvent>();
    bus.subscribe(events::add);
    var notice =
        new dev.mikoto2000.rei.secretary.SecretaryNotifications.Notice(
            "global-one",
            dev.mikoto2000.rei.secretary.SecretaryNotifications.GLOBAL_SCOPE,
            null,
            "GLOBAL_BRIEFING",
            "combined report",
            3,
            "INBOX",
            clock.instant(),
            "report-one");
    service.deliver(notice);
    var event =
        events.stream()
            .filter(e -> e.type() == AgentEventType.ATTENTION_REQUIRED)
            .findFirst()
            .orElseThrow();
    assertEquals(null, event.projectId());
    assertEquals(null, event.sessionId());
    assertEquals(
        "SECRETARY_GLOBAL_INBOX",
        repository
            .list(dev.mikoto2000.rei.secretary.SecretaryNotifications.GLOBAL_SCOPE)
            .getFirst()
            .kind());
    service.deliver(notice);
    assertEquals(
        1,
        repository.list(dev.mikoto2000.rei.secretary.SecretaryNotifications.GLOBAL_SCOPE).size());
  }

  @Test
  void heldFactsSurviveRestartAndReleaseAsOneDigest() {
    var events = new java.util.ArrayList<AgentEvent>();
    bus.subscribe(events::add);
    var life = org.mockito.Mockito.mock(dev.mikoto2000.rei.life.LifeContext.class);
    service.setLifeContext(life);
    bus.publish(waiting("one"));
    bus.publish(waiting("two"));
    time.set(Duration.ofMinutes(3).toNanos());
    bus.publish(waiting("one"));
    bus.publish(waiting("two"));
    assertEquals(
        0, events.stream().filter(e -> e.type() == AgentEventType.ATTENTION_REQUIRED).count());
    assertEquals(
        2, repository.list("project").stream().filter(i -> i.status().equals("HELD")).count());
    service.close();
    repository =
        new AttentionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("attention.db")), clock);
    service = new AttentionService(repository, bus, bus, clock, time::get);
    service.setLifeContext(life);
    org.mockito.Mockito.when(
            life.notificationAllowed(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString()))
        .thenReturn(true);
    service.releaseHeld();
    service.releaseHeld();
    assertEquals(
        1, events.stream().filter(e -> e.type() == AgentEventType.ATTENTION_REQUIRED).count());
    assertEquals(
        1, repository.list("project").stream().filter(i -> i.kind().equals("LIFE_DIGEST")).count());
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

  /** 検証に使う保存と検索の窓口。 */
  AttentionRepository repository;

  /** 検証に使う通知の連携先。 */
  InMemoryAgentEventBus bus;

  /** 検証に使うサービス。 */
  AttentionService service;

  /** 検証に使う時刻。 */
  AtomicLong time;

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner(String run) {
    return new AgentRunContext(run, "session", dir, "project");
  }

  /** 検証で使用する生成元を、指定された条件に合わせて用意する。 */
  AgentEventFactory factory() {
    return new AgentEventFactory(clock);
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repository =
        new AttentionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("attention.db")), clock);
    bus = new InMemoryAgentEventBus();
    time = new AtomicLong();
    service = new AttentionService(repository, bus, bus, clock, time::get);
    service.start();
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    service.close();
  }

  /** {@code waiting} の条件を再現するための補助情報を返す。 */
  AgentEvent waiting(String run) {
    return factory()
        .executionProgress(
            AgentEventType.STAGNATION_UPDATED,
            run,
            new ExecutionProgressPayload(null, 0, 3, 0, 1, "waiting_for_dependency"))
        .withOwnership(owner(run));
  }

  /** 注意の管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void permissionEscalationIsPersistentDeduplicatedAndAckDoesNotGrantPermission() {
    var received = new java.util.ArrayList<AgentEvent>();
    bus.subscribe(received::add);
    var event =
        factory()
            .toolFailed(
                "call",
                "writeMultiFile",
                new ErrorInformation("PermissionRequired", "secret arguments", "REQUIRE_APPROVAL"))
            .withOwnership(owner("run"));
    bus.publish(event);
    bus.publish(event);
    var item = repository.list("project").getFirst();
    assertEquals("APPROVAL_REQUIRED", item.kind());
    assertFalse(item.message().contains("secret"));
    assertEquals(
        1, received.stream().filter(e -> e.type().value().equals("attention.required")).count());
    var reopened =
        new AttentionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("attention.db")), clock);
    assertEquals(item.id(), reopened.list("project").getFirst().id());
    assertThrows(IllegalArgumentException.class, () -> reopened.acknowledge("other", item.id()));
    reopened.acknowledge("project", item.id());
    bus.publish(event);
    assertTrue(reopened.list("project").isEmpty());
  }

  /** 長い待機について、確認済み待機中を必要とすることを確認する。 */
  @Test
  void longWaitRequiresVerifiedWaitingAndProgressResetsItsDeadline() {
    bus.publish(waiting("run"));
    time.set(Duration.ofSeconds(119).toNanos());
    bus.publish(waiting("run"));
    assertTrue(repository.list("project").isEmpty());
    bus.publish(
        factory()
            .executionProgress(
                AgentEventType.STAGNATION_RECOVERED,
                "run",
                new ExecutionProgressPayload(null, 0, 3, 0, 1, "progress"))
            .withOwnership(owner("run")));
    bus.publish(waiting("run"));
    time.addAndGet(Duration.ofSeconds(120).toNanos());
    bus.publish(waiting("run"));
    bus.publish(waiting("run"));
    assertEquals(1, repository.list("project").size());
    assertEquals("LONG_WAIT", repository.list("project").getFirst().kind());
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void terminalAndUnownedEventsDoNotCreateStaleWaitsOrGlobalItems() {
    bus.publish(waiting("run"));
    bus.publish(factory().runCompleted("run", 1).withOwnership(owner("run")));
    var completed = repository.list("project").getFirst();
    assertEquals("RUN_COMPLETED", completed.kind());
    repository.acknowledge("project", completed.id());
    time.set(Duration.ofHours(1).toNanos());
    bus.publish(waiting("run"));
    assertTrue(repository.list("project").isEmpty());
    bus.publish(
        factory().toolFailed("call", "x", new ErrorInformation("PermissionRequired", "x", null)));
    assertTrue(repository.list("project").isEmpty());
    bus.publish(
        factory()
            .executionProgress(
                AgentEventType.STAGNATION_STOPPED,
                "stopped",
                new ExecutionProgressPayload(null, 3, 3, 1, 1, "stopped"))
            .withOwnership(owner("stopped")));
    assertEquals("STAGNATION_STOPPED", repository.list("project").getFirst().kind());
  }

  /** 注意の管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void actualPermissionGuardAndAcknowledgementNeverGrantTheRequestedAction() {
    var approvals =
        new dev.mikoto2000.rei.core.policy.ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("attention.db")), clock);
    var policy =
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null));
    var guard = new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(policy, factory(), bus);
    guard.setApprovals(approvals);
    String arguments = "{\"files\":[{\"path\":\"approval-test.txt\",\"content\":\"test\"}]}";
    assertThrows(
        dev.mikoto2000.rei.core.policy.ToolPermissionException.class,
        () -> guard.check("writeMultiFile", arguments, owner("guard-run")));
    var attention = repository.list("project").getFirst();
    repository.acknowledge("project", attention.id());
    assertThrows(
        dev.mikoto2000.rei.core.policy.ToolPermissionException.class,
        () -> guard.check("writeMultiFile", arguments, owner("guard-run")));
    assertEquals("PENDING", approvals.list("project").getFirst().status());
    assertTrue(repository.list("project").isEmpty());
  }

  /** 注意の管理の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void approvalNoticeIdentifiesTheExactRequestAndDisplaysTheActionAndContinueCommand() {
    var approvals =
        new dev.mikoto2000.rei.core.policy.ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("attention.db")), clock);
    service.setApprovals(approvals);
    var guard =
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
                new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                    true, null, java.util.Set.of(), null)),
            factory(),
            bus);
    guard.setApprovals(approvals);
    assertThrows(
        dev.mikoto2000.rei.core.policy.ToolPermissionException.class,
        () ->
            guard.check(
                "runCommand",
                "{\"request\":{\"command\":\"git status --short\"}}",
                owner("approval-run")));
    var request = approvals.list("project").getFirst();
    var notice = repository.list("project").getFirst();
    assertEquals(request.id(), notice.reference());
    assertEquals("APPROVAL_REQUIRED", notice.kind());
    assertTrue(notice.message().contains("git status --short"));
    assertTrue(notice.message().contains("/approval approve " + request.id() + " --continue"));
    assertTrue(notice.message().contains("有効期限"));
    assertTrue(notice.message().contains("/approval deny " + request.id()));
  }

  /** 注意の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void stoppedGoalProducesOneOwnedAttentionItem() {
    var event =
        new AgentEvent(
            "goal-event",
            0,
            Instant.EPOCH,
            AgentEventType.GOAL_UPDATED,
            1,
            "session",
            null,
            "run",
            null,
            null,
            new GoalLifecyclePayload("goal", "BLOCKED", 3, 3, 20, 20, "budget_exhausted"),
            "project");
    bus.publish(event);
    bus.publish(event);
    assertEquals(1, repository.list("project").size());
    assertEquals("GOAL_STOPPED", repository.list("project").getFirst().kind());
  }

  /** 注意の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellAndWebControlsAcknowledgeOnlyInsideTheOwningProject() {
    bus.publish(
        factory()
            .toolFailed(
                "call", "tool", new ErrorInformation("PermissionDenied", "unsafe message", null))
            .withOwnership(owner("denied")));
    var item = repository.list("project").getFirst();
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var project = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);
    org.mockito.Mockito.when(project.id()).thenReturn("project");
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);
    var command = new AttentionCommand(repository, projects);
    var output = new java.io.StringWriter();
    command.setShellOutput(new java.io.PrintWriter(output));
    var cli = new picocli.CommandLine(command);
    assertEquals(0, cli.execute("show", item.id()));
    assertTrue(output.toString().contains("POLICY_DENIED"));
    var api = new dev.mikoto2000.rei.web.AttentionController(repository);
    assertThrows(IllegalArgumentException.class, () -> api.acknowledge("other", item.id()));
    assertEquals(1, api.list("project").size());
    assertEquals(0, cli.execute("ack", item.id()));
    assertEquals("ACKNOWLEDGED", api.show("project", item.id()).status());
    assertTrue(api.list("project").isEmpty());
  }

  /** 注意の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void terminalRunsProduceDistinctDeduplicatedFactsWithoutErrorDetails() {
    var complete = factory().runCompleted("complete", 1).withOwnership(owner("complete"));
    var failed =
        factory()
            .runFailed("failed", new ErrorInformation("Failure", "secret token=private", null))
            .withOwnership(owner("failed"));
    bus.publish(complete);
    bus.publish(complete);
    bus.publish(failed);
    bus.publish(failed);
    assertEquals(2, repository.list("project").size());
    assertEquals(
        java.util.Set.of("RUN_COMPLETED", "RUN_FAILED"),
        repository.list("project").stream()
            .map(AttentionRepository.Item::kind)
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(repository.list("project").stream().noneMatch(i -> i.message().contains("private")));
    var item =
        repository.list("project").stream()
            .filter(i -> i.kind().equals("RUN_COMPLETED"))
            .findFirst()
            .orElseThrow();
    repository.acknowledge("project", item.id());
    bus.publish(complete);
    assertEquals(1, repository.list("project").size());
  }

  /** {@code dependency} の条件を再現するための補助情報を返す。 */
  AgentEvent dependency(String id, AgentEventType type, String kind, String state, String reason) {
    return new AgentEvent(
        "event-" + id,
        0,
        Instant.EPOCH,
        type,
        1,
        "session",
        null,
        null,
        id,
        null,
        new DependencyStatusPayload(id, kind, state, reason, 0),
        "project");
  }

  /** 注意の管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void humanDecisionAndDependencyTerminalEventsKeepTheirOwnIdentity() {
    var events = new java.util.ArrayList<AgentEvent>();
    bus.subscribe(events::add);
    var question =
        dependency(
            "question",
            AgentEventType.DEPENDENCY_UPDATED,
            "USER_ANSWER",
            "WAITING",
            "user_answer_waiting");
    var failed =
        dependency(
            "failed",
            AgentEventType.DEPENDENCY_FAILED,
            "HTTP_STATUS",
            "FAILED",
            "dependency_deadline_expired");
    var completed =
        dependency(
            "complete",
            AgentEventType.DEPENDENCY_COMPLETED,
            "FILE_EXISTS",
            "COMPLETED",
            "file_exists");
    bus.publish(question);
    bus.publish(question);
    bus.publish(failed);
    bus.publish(completed);
    assertEquals(
        java.util.Set.of("DECISION_REQUIRED", "DEPENDENCY_FAILED", "DEPENDENCY_COMPLETED"),
        repository.list("project").stream()
            .map(AttentionRepository.Item::kind)
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(repository.list("project").stream().allMatch(i -> i.runId() == null));
    assertEquals(
        3,
        events.stream()
            .filter(e -> e.type() == AgentEventType.ATTENTION_REQUIRED && e.runId() == null)
            .count());
    assertTrue(repository.list("other").isEmpty());
    var restored =
        new AttentionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("attention.db")), clock);
    assertEquals(3, restored.list("project").size());
    assertTrue(restored.list("project").stream().allMatch(i -> i.runId() == null));
  }

  /** 注意の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void inconsistentDependencyEventsDoNotCreateAttention() {
    bus.publish(
        dependency("bad", AgentEventType.DEPENDENCY_COMPLETED, "FILE_EXISTS", "FAILED", "failure"));
    var valid =
        dependency(
            "valid",
            AgentEventType.DEPENDENCY_COMPLETED,
            "FILE_EXISTS",
            "COMPLETED",
            "file_exists");
    bus.publish(
        new AgentEvent(
            valid.id(),
            0,
            valid.timestamp(),
            valid.type(),
            1,
            valid.sessionId(),
            null,
            null,
            "different",
            null,
            valid.payload(),
            valid.projectId()));
    bus.publish(
        new AgentEvent(
            valid.id(),
            0,
            valid.timestamp(),
            valid.type(),
            1,
            null,
            null,
            null,
            "valid",
            null,
            valid.payload(),
            valid.projectId()));
    assertTrue(repository.list("project").isEmpty());
  }
}
