package dev.mikoto2000.rei.core.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.event.ProjectAgentEventSubscriber;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 依存条件観測内容サービスの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DependencyObservationServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T00:00:00Z");

  /** 検証に使うプロジェクト。 */
  final String project = UUID.randomUUID().toString();

  /** {@code repo} の条件を再現するための補助情報を返す。 */
  PersistentDependencyRepository repo(Instant time) {
    return new PersistentDependencyRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("deps.db")),
        Clock.fixed(time, ZoneOffset.UTC));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  PersistentDependencyRepository.Entry create(PersistentDependencyRepository repo) {
    return repo.create(
        new AgentRunContext("r", "s", dir, project),
        new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "result", null),
        Duration.ofHours(1),
        List.of());
  }

  /** 検証で使用する判断基準を、指定された条件に合わせて用意する。 */
  ToolPermissionPolicy policy(boolean enabled) {
    return new ToolPermissionPolicy(new ToolPermissionProperties(enabled, null, null, null));
  }

  /** 外部条件の待機の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void automaticObservationsRequireEnabledWatcherAndEnforcedAutoPermission() {
    var repo = repo(now);
    var entry = create(repo);
    var probe = mock(DependencyProbe.class);
    var events = new ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var disabled =
        new DependencyObservationService(
            repo, probe, new DependencyWatcherProperties(false), policy(true), events::add);
    disabled.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(probe);
    var legacy =
        new DependencyObservationService(
            repo, probe, new DependencyWatcherProperties(true), policy(false), events::add);
    legacy.tick();
    verifyNoInteractions(probe);
    when(probe.probe(any()))
        .thenReturn(
            new DependencyObservation(entry.id(), DependencyState.COMPLETED, "file_exists"));
    var active =
        new DependencyObservationService(
            repo, probe, new DependencyWatcherProperties(true), policy(true), events::add);
    active.tick();
    assertEquals(DependencyState.COMPLETED, repo.get(project, entry.id()).state());
    verify(probe, times(1)).probe(any());
  }

  /** 外部条件の待機の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void deadlineAndPrerequisitesDoNotProbeAndStaleCompletionCannotUndoCancel() {
    var repo = repo(now);
    var parent = create(repo);
    var child =
        repo.create(
            new AgentRunContext("r", "s", dir, project),
            new DependencySpec(DependencySpec.Kind.FILE_EXISTS, "child", null),
            Duration.ofHours(1),
            List.of(parent.id()));
    var probe = mock(DependencyProbe.class);
    var service =
        new DependencyObservationService(
            repo, probe, new DependencyWatcherProperties(false), policy(true), e -> {});
    assertEquals(DependencyState.BLOCKED, service.inspect(project, child.id(), false).state());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(probe);
    when(probe.probe(any()))
        .thenAnswer(
            invocation -> {
              repo.cancel(project, parent.id());
              return new DependencyObservation(
                  parent.id(), DependencyState.COMPLETED, "file_exists");
            });
    assertEquals(DependencyState.CANCELLED, service.inspect(project, parent.id(), false).state());
    var late =
        new DependencyObservationService(
            repo(now.plusSeconds(3600)),
            probe,
            new DependencyWatcherProperties(false),
            policy(true),
            e -> {});
    clearInvocations(probe);
    assertEquals(DependencyState.FAILED, late.inspect(project, child.id(), false).state());
    verifyNoInteractions(probe);
  }

  /** 永続化された事実再試行回数について、識別情報を維持することを確認する。 */
  @Test
  void durableFactRetriesKeepIdentityAndDriveExistingEventScheduler() {
    var repo = repo(now);
    var entry = create(repo);
    var probe = mock(DependencyProbe.class);
    when(probe.probe(any()))
        .thenReturn(
            new DependencyObservation(entry.id(), DependencyState.COMPLETED, "file_exists"));
    var bus = new InMemoryAgentEventBus();
    var store = new ProjectAgentEventStore(dir.resolve("history"));
    try (var subscriber = new ProjectAgentEventSubscriber(bus, store)) {
      var scheduler =
          new dev.mikoto2000.rei.temporal.PersistentAgentScheduler(
              new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("timer.db")),
              Clock.fixed(now, ZoneOffset.UTC));
      String timer;
      try (var scope = AgentRunScope.open(new AgentRunContext("r", "s", dir, project))) {
        timer =
            scheduler
                .scheduleOnEvent(
                    entry.id(),
                    AgentEventType.DEPENDENCY_COMPLETED,
                    Duration.ofHours(1),
                    "Continue",
                    "s")
                .id();
      }
      scheduler.activate(project, timer);
      try (var triggers =
          new dev.mikoto2000.rei.temporal.AgentEventTriggerService(scheduler, bus, store)) {
        var failed =
            new DependencyObservationService(
                repo,
                probe,
                new DependencyWatcherProperties(false),
                policy(true),
                e -> {
                  throw new IllegalStateException("delivery unavailable");
                });
        failed.inspect(project, entry.id(), false);
        assertFalse(repo.pendingFacts().isEmpty());
        String eventId = repo.pendingFacts().getLast().eventId();
        var restored =
            new DependencyObservationService(
                repo(now), probe, new DependencyWatcherProperties(false), policy(true), bus);
        restored.tick();
        assertTrue(repo.pendingFacts().isEmpty());
        assertEquals(timer, scheduler.claimDue().orElseThrow().task().id());
        var matching =
            store.recent(project, 10).stream().filter(e -> e.id().equals(eventId)).toList();
        assertEquals(1, matching.size());
        assertEquals(AgentEventType.DEPENDENCY_COMPLETED, matching.getFirst().type());
        assertNull(matching.getFirst().runId());
        assertEquals(entry.id(), matching.getFirst().correlationId());
      }
    }
  }

  /** 外部条件の待機で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void readOnlyInspectionCannotPerformHttpAndAutomaticNetworkWaitRequiresGrant() {
    var repo = repo(now);
    var entry =
        repo.create(
            new AgentRunContext("r", "s", dir, project),
            new DependencySpec(DependencySpec.Kind.HTTP_STATUS, "https://example.com", "200"),
            Duration.ofHours(1),
            List.of());
    var probe = mock(DependencyProbe.class);
    var service =
        new DependencyObservationService(
            repo, probe, new DependencyWatcherProperties(true), policy(true), e -> {});
    assertThrows(IllegalArgumentException.class, () -> service.inspect(project, entry.id(), false));
    service.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(probe);
    assertEquals("permission_required", repo.get(project, entry.id()).reason());
  }

  /** 外部条件の待機で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missingOrMovedOwnerSessionBlocksObservationUntilOwnerIsValid() {
    var repo = repo(now);
    var entry = create(repo);
    var probe = mock(DependencyProbe.class);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var sessions = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    when(projects.registeredProjects())
        .thenReturn(
            List.of(new dev.mikoto2000.rei.core.project.ProjectContext(project, "test", dir)));
    var service =
        new DependencyObservationService(
            repo,
            probe,
            new DependencyWatcherProperties(false),
            policy(true),
            e -> {},
            projects,
            sessions);
    when(sessions.findById("s")).thenReturn(Optional.empty());
    assertEquals(DependencyState.BLOCKED, service.inspect(project, entry.id(), false).state());
    when(sessions.findById("s"))
        .thenReturn(
            Optional.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "s", "other", "test", now, now)));
    assertEquals(DependencyState.BLOCKED, service.inspect(project, entry.id(), false).state());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(probe);
    when(sessions.findById("s"))
        .thenReturn(
            Optional.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "s", project, "test", now, now)));
    when(probe.probe(any()))
        .thenReturn(
            new DependencyObservation(entry.id(), DependencyState.COMPLETED, "file_exists"));
    assertEquals(DependencyState.COMPLETED, service.inspect(project, entry.id(), false).state());
    verify(probe).probe(any());
  }
}
