package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.DependencyStatusPayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** イベント実行条件の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class EventTriggerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使うプロジェクト。 */
  final String project = UUID.randomUUID().toString();

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T00:00:00Z");

  /** {@code scheduler} の条件を再現するための補助情報を返す。 */
  PersistentAgentScheduler scheduler(Instant time) {
    return new PersistentAgentScheduler(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("events.db")),
        Clock.fixed(time, ZoneOffset.UTC));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  String create(PersistentAgentScheduler scheduler) {
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", "session", dir, project))) {
      return new SchedulerTools(scheduler)
          .scheduleOnEvent("source", "AGENT_RUN_COMPLETED", "1h", "Continue", "session")
          .id();
    }
  }

  /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
  AgentEvent event(String project, String session, String run, Instant time, AgentEventType type) {
    return new AgentEvent(
        UUID.randomUUID().toString(),
        0,
        time,
        type,
        1,
        session,
        null,
        run,
        null,
        null,
        new AgentRunCompletedPayload(run, 1),
        project);
  }

  /** {@code completed} の条件を再現するための補助情報を返す。 */
  AgentEvent completed() {
    return event(
        project, "session", "source", now.plusSeconds(1), AgentEventType.AGENT_RUN_COMPLETED);
  }

  /** 時刻と期間の解釈で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void explicitActivationExactOwnershipAndDuplicateSuppression() {
    var scheduler = scheduler(now.plusSeconds(2));
    String id = create(scheduler);
    scheduler.signalEvent(completed());
    assertTrue(scheduler.claimDue().isEmpty());
    scheduler.activate(project, id);
    assertEquals("WAITING_EVENT", scheduler.get(project, id).status());
    scheduler.signalEvent(completed()); // Before activation, even when replayed.
    scheduler.signalEvent(
        event(
            "other", "session", "source", now.plusSeconds(2), AgentEventType.AGENT_RUN_COMPLETED));
    scheduler.signalEvent(
        event(project, "other", "source", now.plusSeconds(2), AgentEventType.AGENT_RUN_COMPLETED));
    scheduler.signalEvent(
        event(project, "session", "other", now.plusSeconds(2), AgentEventType.AGENT_RUN_COMPLETED));
    assertTrue(scheduler.claimDue().isEmpty());
    var later = scheduler(now.plusSeconds(4));
    var matching =
        event(project, "session", "source", now.plusSeconds(3), AgentEventType.AGENT_RUN_COMPLETED);
    later.signalEvent(matching);
    later.signalEvent(matching);
    var claim = later.claimDue().orElseThrow();
    assertEquals(matching.id(), later.eventTrigger(project, id).orElseThrow().matchedEventId());
    later.finish(claim, "COMPLETED", "ok");
    later.signalEvent(matching);
    assertTrue(later.claimDue().isEmpty());
    assertEquals(
        1, later.history(project, id).stream().filter(h -> h.status().equals("SCHEDULED")).count());
  }

  /** 時刻と期間の解釈の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void expiryAndCancellationNeverDispatchByTimeAlone() {
    var scheduler = scheduler(now);
    String id = create(scheduler);
    scheduler.activate(project, id);
    assertTrue(scheduler(now.plusSeconds(3600)).claimDue().isEmpty());
    assertEquals("FAILED", scheduler.get(project, id).status());
    String cancelled = create(scheduler);
    scheduler.activate(project, cancelled);
    scheduler.cancel(project, cancelled);
    scheduler(now.plusSeconds(2)).signalEvent(completed());
    assertTrue(scheduler(now.plusSeconds(2)).claimDue().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> scheduler.eventTrigger("other", cancelled));
  }

  /** 時刻と期間の解釈で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void restartReplaysPersistedEventAndNeverReplaysAnUncertainClaim() {
    var first = scheduler(now);
    String id = create(first);
    first.activate(project, id);
    var store = new ProjectAgentEventStore(dir.resolve("history"));
    store.append(completed());
    var restarted = scheduler(now.plusSeconds(2));
    var bus = new InMemoryAgentEventBus();
    try (var triggers = new AgentEventTriggerService(restarted, bus, store)) {
      triggers.tick();
      var claim = restarted.claimDue().orElseThrow();
      assertEquals(id, claim.task().id());
      triggers.tick();
      bus.publish(completed());
      assertTrue(restarted.claimDue().isEmpty());
    }
    try (var triggers = new AgentEventTriggerService(scheduler(now.plusSeconds(3)), bus, store)) {
      triggers.tick();
      assertTrue(scheduler(now.plusSeconds(3)).claimDue().isEmpty());
    }
  }

  /** 時刻と期間の解釈で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void liveBusSubscriptionIsClosedAndIgnoresUnrelatedEvents() {
    var first = scheduler(now);
    String id = create(first);
    first.activate(project, id);
    var bus = new InMemoryAgentEventBus();
    var later = scheduler(now.plusSeconds(2));
    var service =
        new AgentEventTriggerService(
            later, bus, new ProjectAgentEventStore(dir.resolve("history")));
    bus.publish(
        event(project, "session", "source", now.plusSeconds(1), AgentEventType.AGENT_RUN_FAILED));
    assertTrue(later.claimDue().isEmpty());
    bus.publish(completed());
    assertTrue(later.claimDue().isPresent());
    service.close();
    String second = create(first);
    first.activate(project, second);
    bus.publish(completed());
    assertEquals("WAITING_EVENT", later.get(project, second).status());
  }

  /** 時刻と期間の解釈で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidConfigurationIsRejectedWithoutPartialRows() {
    var scheduler = scheduler(now);
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", "session", dir, project))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              scheduler.scheduleOnEvent(
                  "source", AgentEventType.TOOL_STARTED, Duration.ofHours(1), "x", "session"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              scheduler.scheduleOnEvent(
                  "", AgentEventType.AGENT_RUN_COMPLETED, Duration.ofHours(1), "x", "session"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              scheduler.scheduleOnEvent(
                  "source", AgentEventType.AGENT_RUN_COMPLETED, Duration.ZERO, "x", "session"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SchedulerTools(scheduler)
                  .scheduleOnEvent("source", "AGENT_RUN_COMPLETED", "1h", "x", "other"));
    }
    assertTrue(scheduler.list(project).isEmpty());
  }

  /** 時刻と期間の解釈で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void persistedReplayCursorContinuesAcrossMultiplePagesAndRestarts() {
    var first = scheduler(now);
    String id = create(first);
    first.activate(project, id);
    var store = new ProjectAgentEventStore(dir.resolve("history"));
    for (int i = 0; i < 300; i++)
      store.append(
          event(
              project,
              "session",
              "unrelated-" + i,
              now.plusSeconds(1),
              AgentEventType.AGENT_RUN_COMPLETED));
    store.append(completed());
    var later = scheduler(now.plusSeconds(2));
    var bus = new InMemoryAgentEventBus();
    try (var triggers = new AgentEventTriggerService(later, bus, store)) {
      triggers.tick();
      assertTrue(later.claimDue().isEmpty());
    }
    long offset = later.replayCursor(project).offset();
    assertTrue(offset > 0);
    try (var triggers = new AgentEventTriggerService(scheduler(now.plusSeconds(2)), bus, store)) {
      triggers.tick();
      assertTrue(later.claimDue().isEmpty());
      assertTrue(later.replayCursor(project).offset() > offset);
      triggers.tick();
      assertEquals(id, later.claimDue().orElseThrow().task().id());
    }
  }

  /** 時刻と期間の解釈で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void concurrentDuplicateSignalsHaveOneTransitionAndOneClaim() throws Exception {
    var first = scheduler(now);
    String id = create(first);
    first.activate(project, id);
    var one = scheduler(now.plusSeconds(2));
    var two = scheduler(now.plusSeconds(2));
    var event = completed();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> one.signalEvent(event));
      var b = pool.submit(() -> two.signalEvent(event));
      a.get();
      b.get();
      var c = pool.submit(one::claimDue);
      var d = pool.submit(two::claimDue);
      assertEquals(1, (c.get().isPresent() ? 1 : 0) + (d.get().isPresent() ? 1 : 0));
    }
    assertEquals(
        1, one.history(project, id).stream().filter(h -> h.status().equals("SCHEDULED")).count());
  }

  /** イベント実行先への引き渡しについて、取得済み所有者を利用することを確認する。 */
  @Test
  void eventDispatchUsesCapturedOwnerAndExistingFifoOnlyWhenEnabled() {
    var first = scheduler(now);
    String id = create(first);
    first.activate(project, id);
    var later = scheduler(now.plusSeconds(2));
    var projects = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.registeredProjects())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.core.project.ProjectContext(project, "test", dir)));
    var sessions =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    org.mockito.Mockito.when(sessions.findById("session"))
        .thenReturn(
            java.util.Optional.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "session", project, "test", now, now)));
    var chat = org.mockito.Mockito.mock(ChatExecutionService.class);
    org.mockito.Mockito.when(
            chat.execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("Continue"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(ChatExecutionResult.success("done", false));
    var jobs = new java.util.ArrayDeque<Runnable>();
    var router = new ConversationInputRouter(jobs::add, (owner, prompt, queue) -> {});
    var permissions =
        new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true, null, null, null);
    var dispatcher =
        new AgentScheduleDispatcher(
            later,
            new AgentSchedulerProperties(true),
            permissions,
            projects,
            sessions,
            router,
            chat);
    dispatcher.tick();
    assertTrue(jobs.isEmpty());
    later.signalEvent(completed());
    var disabled =
        new AgentScheduleDispatcher(
            later,
            new AgentSchedulerProperties(false),
            permissions,
            projects,
            sessions,
            router,
            chat);
    disabled.tick();
    assertTrue(jobs.isEmpty());
    dispatcher.tick();
    org.mockito.Mockito.verifyNoInteractions(chat);
    jobs.remove().run();
    assertTrue(jobs.isEmpty());
    var capture = org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
    org.mockito.Mockito.verify(chat)
        .execute(
            capture.capture(),
            org.mockito.ArgumentMatchers.eq("Continue"),
            org.mockito.ArgumentMatchers.any());
    assertEquals(project, capture.getValue().projectId());
    assertEquals("session", capture.getValue().conversationId());
    assertEquals("COMPLETED", later.get(project, id).status());
    dispatcher.tick();
    assertTrue(jobs.isEmpty());
  }

  /** 時刻と期間の解釈で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void cursorFromBeforeNewActivationCannotSkipItsReplay() {
    var first = scheduler(now);
    String id = create(first);
    first.activate(project, id);
    var old = first.replayCursor(project);
    String next = create(first);
    first.activate(project, next);
    first.saveReplayCursor(project, old, 999, false);
    assertEquals(0, first.replayCursor(project).offset());
  }

  /** 時刻と期間の解釈で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void replayNeverImportsEventOwnershipFromAnotherProjectFile() throws Exception {
    var first = scheduler(now);
    String id = create(first);
    first.activate(project, id);
    String other = UUID.randomUUID().toString();
    String otherId;
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", "session", dir, other))) {
      otherId =
          first
              .scheduleOnEvent(
                  "source",
                  AgentEventType.AGENT_RUN_COMPLETED,
                  Duration.ofHours(1),
                  "other",
                  "session")
              .id();
    }
    first.activate(other, otherId);
    var source = new ProjectAgentEventStore(dir.resolve("source"));
    source.append(
        event(other, "session", "source", now.plusSeconds(1), AgentEventType.AGENT_RUN_COMPLETED));
    Path target = dir.resolve("history/projects").resolve(project).resolve("events/events.jsonl");
    java.nio.file.Files.createDirectories(target.getParent());
    java.nio.file.Files.copy(
        dir.resolve("source/projects").resolve(other).resolve("events/events.jsonl"), target);
    var later = scheduler(now.plusSeconds(2));
    try (var service =
        new AgentEventTriggerService(
            later,
            new InMemoryAgentEventBus(),
            new ProjectAgentEventStore(dir.resolve("history")))) {
      service.tick();
      assertTrue(later.claimDue().isEmpty());
      assertEquals("WAITING_EVENT", later.get(project, id).status());
      assertEquals("WAITING_EVENT", later.get(other, otherId).status());
    }
  }

  /** 時刻と期間の解釈で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void dependencyEventMustHaveMatchingTypedTerminalStateWithoutPretendingToBeARun() {
    var scheduler = scheduler(now.plusSeconds(2));
    String id;
    try (var scope = AgentRunScope.open(new AgentRunContext("parent", "session", dir, project))) {
      id =
          scheduler
              .scheduleOnEvent(
                  "dependency",
                  AgentEventType.DEPENDENCY_COMPLETED,
                  Duration.ofHours(1),
                  "Continue",
                  "session")
              .id();
    }
    scheduler.activate(project, id);
    var inconsistent =
        new AgentEvent(
            UUID.randomUUID().toString(),
            0,
            now.plusSeconds(2),
            AgentEventType.DEPENDENCY_COMPLETED,
            1,
            "session",
            null,
            null,
            "dependency",
            null,
            new DependencyStatusPayload("dependency", "FILE_EXISTS", "FAILED", "failure", 1),
            project);
    scheduler.signalEvent(inconsistent);
    assertTrue(scheduler.claimDue().isEmpty());
    var valid =
        new AgentEvent(
            UUID.randomUUID().toString(),
            0,
            now.plusSeconds(2),
            AgentEventType.DEPENDENCY_COMPLETED,
            1,
            "session",
            null,
            null,
            "dependency",
            null,
            new DependencyStatusPayload("dependency", "FILE_EXISTS", "COMPLETED", "file_exists", 2),
            project);
    scheduler.signalEvent(valid);
    assertEquals(id, scheduler.claimDue().orElseThrow().task().id());
  }
}
