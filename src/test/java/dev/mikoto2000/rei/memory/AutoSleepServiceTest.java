package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.memory.configuration.AutoSleepProperties;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.service.AutoSleepService;
import dev.mikoto2000.rei.memory.service.MemoryCandidateExtractor;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryResolver;
import dev.mikoto2000.rei.memory.service.SleepService;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 自動待機サービスの結果と状態の契約を検証するテスト。 */
class AutoSleepServiceTest {
  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T00:00:00Z");

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  /** 検証に使う待機。 */
  final SleepService sleep = mock(SleepService.class);

  /** 検証に使う作業状況。 */
  final DefaultAgentActivityTracker activity =
      new DefaultAgentActivityTracker(Clock.fixed(now.minusSeconds(600), ZoneOffset.UTC));

  /** 検証に使う所有者。 */
  final AgentRunContext owner = new AgentRunContext("r", "s", java.nio.file.Path.of("."), "p");

  /** 検証に使うサービス。 */
  AutoSleepService service;

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    if (service != null) service.close();
  }

  /** 検証ごとに状態を分離し、対象を操作できる環境を用意する。 */
  void setup(boolean enabled) {
    service =
        new AutoSleepService(
            stubState(sleep),
            new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null),
            new AutoSleepProperties(enabled, Duration.ofMinutes(5), 2, Duration.ofMinutes(10)),
            activity,
            clock);
    when(sleep.unsleptTurns("s")).thenReturn(2L);
    service.afterTerminal(owner);
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledDoesNotReadOrRun() {
    setup(false);
    service.tick();
    verifyNoInteractions(sleep);
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void invalidLimitsAreRejectedAndDefaultsAreOptIn() {
    assertFalse(new AutoSleepProperties(false, null, 0, null).enabled());
    assertThrows(
        IllegalArgumentException.class,
        () -> new AutoSleepProperties(true, Duration.ZERO, 1, null));
    assertThrows(
        IllegalArgumentException.class, () -> new AutoSleepProperties(true, null, -1, null));
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void idleSessionRunsOneBatchAndDoesNotImmediatelyRepeat() throws Exception {
    setup(true);
    CountDownLatch done = new CountDownLatch(1);
    doAnswer(
            a -> {
              done.countDown();
              return emptyReport();
            })
        .when(sleep)
        .sleep(eq("s"), eq("p"), eq(false), any(), any());
    service.tick();
    assertTrue(done.await(2, TimeUnit.SECONDS));
    service.tick();
    verify(sleep, times(1)).sleep(eq("s"), eq("p"), eq(false), any(), any());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void busyOrRecentInputNeverStarts() {
    setup(true);
    activity.recordAgentStarted(now);
    service.tick();
    activity.recordAgentCompleted(now);
    service.tick();
    verify(sleep, never()).sleep(anyString(), anyString(), anyBoolean(), any(), any());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void insufficientTurnsNeverStarts() {
    setup(true);
    when(sleep.unsleptTurns("s")).thenReturn(1L);
    service.tick();
    verify(sleep, never()).sleep(anyString(), anyString(), anyBoolean(), any(), any());
  }

  /** 長期記憶の活用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void newActivityInvalidatesCancellationGuard() throws Exception {
    setup(true);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            a -> {
              java.util.function.BooleanSupplier guard = a.getArgument(3);
              entered.countDown();
              release.await(2, TimeUnit.SECONDS);
              cancelled.set(guard.getAsBoolean());
              return emptyReport();
            })
        .when(sleep)
        .sleep(eq("s"), eq("p"), eq(false), any(), any());
    service.tick();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    activity.recordUserActivity(now);
    release.countDown();
    service.close();
    assertTrue(cancelled.get());
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void failureIsContainedAndRateLimited() throws Exception {
    setup(true);
    CountDownLatch entered = new CountDownLatch(1);
    doAnswer(
            a -> {
              entered.countDown();
              throw new IllegalStateException("model unavailable");
            })
        .when(sleep)
        .sleep(eq("s"), eq("p"), eq(false), any(), any());
    assertDoesNotThrow(service::tick);
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    service.tick();
    verify(sleep, times(1)).sleep(eq("s"), eq("p"), eq(false), any(), any());
  }

  /** 長期記憶の活用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAlsoProtectsManualSleepPersistence() {
    var turns = dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory();
    var repository = mock(MemoryRepository.class);
    var extractor = mock(MemoryCandidateExtractor.class);
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);
    var real = new SleepService(repository, turns, extractor, mock(MemoryResolver.class), props);
    assertThrows(CancellationException.class, () -> real.sleep("s", "p", false, () -> true));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(extractor);
    verify(repository, never()).transaction(any());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void savedSessionCanSleepWithoutNewTerminalTurn() throws Exception {
    setup(true);
    var saved = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    when(saved.completionSnapshot())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "old", "00000000-0000-0000-0000-000000000001", "old", now, now)));
    when(projects.completionProjects())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.core.project.ProjectContext(
                    "00000000-0000-0000-0000-000000000001", "p", java.nio.file.Path.of("."))));
    service.setStartupSources(saved, projects);
    when(sleep.unsleptTurns("s")).thenReturn(0L);
    when(sleep.unsleptTurns("old")).thenReturn(2L);
    var done = new CountDownLatch(1);
    doAnswer(
            a -> {
              done.countDown();
              return emptyReport();
            })
        .when(sleep)
        .sleep(eq("old"), eq("00000000-0000-0000-0000-000000000001"), eq(false), any(), any());
    service.tick();
    assertTrue(done.await(2, TimeUnit.SECONDS));
    verify(saved, times(1)).completionSnapshot();
  }

  /** 検証に使うプロジェクト。 */
  final String project = "00000000-0000-0000-0000-000000000001";

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void startupDiscoveryRespectsDisabledBusyAndIdleGates() {
    var saved = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    setup(false);
    service.setStartupSources(saved, projects);
    service.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(saved, projects, sleep);
    service.close();
    setup(true);
    service.setStartupSources(saved, projects);
    activity.recordAgentStarted(now);
    service.tick();
    activity.recordAgentCompleted(now);
    service.tick();
    verifyNoInteractions(saved, projects);
    verify(sleep, never()).unsleptTurns(anyString());
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void startupWalkContinuesBeyondFirst256MetadataRows() throws Exception {
    setup(true);
    when(sleep.unsleptTurns("s")).thenReturn(0L);
    var saved = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var rows = new java.util.ArrayList<dev.mikoto2000.rei.application.session.SessionMetadata>();
    for (int i = 0; i < 300; i++)
      rows.add(
          new dev.mikoto2000.rei.application.session.SessionMetadata(
              "old" + i, project, "old", now, now));
    when(saved.completionSnapshot()).thenReturn(java.util.List.copyOf(rows));
    when(projects.completionProjects())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.core.project.ProjectContext(
                    project, "p", java.nio.file.Path.of("."))));
    service.setStartupSources(saved, projects);
    when(sleep.unsleptTurns("old299")).thenReturn(2L);
    var done = new CountDownLatch(1);
    doAnswer(
            a -> {
              done.countDown();
              return emptyReport();
            })
        .when(sleep)
        .sleep(eq("old299"), eq(project), eq(false), any(), any());
    service.tick();
    verify(sleep, never()).unsleptTurns("old299");
    service.tick();
    assertTrue(done.await(2, TimeUnit.SECONDS));
    verify(saved, times(1)).completionSnapshot();
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unregisteredAndMismatchedProjectMetadataAreNotRead() {
    setup(true);
    when(sleep.unsleptTurns("s")).thenReturn(0L);
    var saved = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    String mismatch = "project:00000000-0000-0000-0000-000000000002:s";
    when(saved.completionSnapshot())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    "unknown", "missing", "old", now, now),
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    mismatch, project, "old", now, now)));
    when(projects.completionProjects())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.core.project.ProjectContext(
                    project, "p", java.nio.file.Path.of("."))));
    service.setStartupSources(saved, projects);
    service.tick();
    verify(sleep, never()).unsleptTurns("unknown");
    verify(sleep, never()).unsleptTurns(mismatch);
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void reopenedFileRepositorySuppliesStartupSession(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    var file = dir.resolve("sessions.json");
    var original = new dev.mikoto2000.rei.conversation.FileSessionRepository(file);
    original.accept(
        new dev.mikoto2000.rei.application.session.SessionMetadata(
            "saved", project, "saved", now, now),
        () -> {});
    var restarted = new dev.mikoto2000.rei.conversation.FileSessionRepository(file);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    when(projects.completionProjects())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.core.project.ProjectContext(project, "p", dir)));
    setup(true);
    when(sleep.unsleptTurns("s")).thenReturn(0L);
    when(sleep.unsleptTurns("saved")).thenReturn(2L);
    service.setStartupSources(restarted, projects);
    var done = new CountDownLatch(1);
    doAnswer(
            a -> {
              done.countDown();
              return emptyReport();
            })
        .when(sleep)
        .sleep(eq("saved"), eq(project), eq(false), any(), any());
    service.tick();
    assertTrue(done.await(2, TimeUnit.SECONDS));
  }

  /** {@code stubState} の条件を再現するための補助情報を返す。 */
  static SleepService stubState(SleepService sleep) {
    when(sleep.autoState(anyString(), anyString()))
        .thenAnswer(
            a ->
                new dev.mikoto2000.rei.memory.service.MemoryRepository.AutoState(
                    a.getArgument(0), a.getArgument(1), 0, 0, null, null, null, null, 0));
    return sleep;
  }

  /** {@code emptyReport} の条件を再現するための補助情報を返す。 */
  static SleepService.Report emptyReport() {
    var report = mock(SleepService.Report.class);
    when(report.run()).thenReturn(mock(dev.mikoto2000.rei.memory.service.SleepRun.class));
    return report;
  }
}
