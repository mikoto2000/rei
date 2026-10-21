package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.memory.configuration.AutoSleepProperties;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.service.AutoSleepExecution;
import dev.mikoto2000.rei.memory.service.AutoSleepService;
import dev.mikoto2000.rei.memory.service.MemoryCandidateExtractor;
import dev.mikoto2000.rei.memory.service.MemoryEvents;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryResolver;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.service.SleepRun;
import dev.mikoto2000.rei.memory.service.SleepService;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 自動待機失敗情報の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class AutoSleepFailureTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う記憶。 */
  final MemoryProperties memory = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);

  /** 検証に使う設定。 */
  final AutoSleepProperties config =
      new AutoSleepProperties(true, Duration.ofSeconds(1), 1, Duration.ofSeconds(1));

  /** 検証に使うやり取り一覧。 */
  final ConversationTurnStore turns = ConversationTurnStore.inMemory();

  /** 検証に使う呼び出し回数。 */
  final AtomicInteger calls = new AtomicInteger();

  /** 検証に使う待機する。 */
  final List<Duration> waits = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** 検証に使う失敗情報。 */
  final AtomicReference<RuntimeException> failure = new AtomicReference<>();

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final MutableClock clock = new MutableClock();

  /** 検証に使う保存と検索の窓口。 */
  MemoryRepository repository;

  /** 検証に使う待機。 */
  SleepService sleep;

  /** 検証に使う自動。 */
  AutoSleepService auto;

  /** 通知された出来事と順序を検証するための記録。 */
  MemoryEvents events = mock(MemoryEvents.class);

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repository = repository();
    MemoryCandidateExtractor extractor =
        new MemoryCandidateExtractor() {
          /** {@code extract} の条件を再現するための補助情報を返す。 */
          public List<dev.mikoto2000.rei.memory.model.MemoryCandidate> extract(
              List<ConversationTurnStore.Turn> batch) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            calls.incrementAndGet();
            var error = failure.get();
            if (error != null) throw error;
            return List.of();
          }

          /** {@code extract} の条件を再現するための補助情報を返す。 */
          public List<dev.mikoto2000.rei.memory.model.MemoryCandidate> extract(
              List<ConversationTurnStore.Turn> batch,
              dev.mikoto2000.rei.llm.ModelCallBudget budget) {
            waits.add(((AutoSleepExecution) budget).waitTime());
            return MemoryCandidateExtractor.super.extract(batch, budget);
          }
        };
    sleep =
        new SleepService(
            repository, turns, extractor, new MemoryResolver((c, e) -> null, memory), memory);
    auto = create();
    add("s");
  }

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  MemoryRepository repository() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    return new MemoryRepository(ds, new MemoryService(ds, memory));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  AutoSleepService create() {
    var result =
        new AutoSleepService(
            sleep,
            memory,
            config,
            new DefaultAgentActivityTracker(
                Clock.fixed(clock.instant().minusSeconds(600), ZoneOffset.UTC)),
            clock);
    result.setEvents(events);
    return result;
  }

  /** 検証用に、新たな対象を登録し、後から利用できるようにする。 */
  void add(String session) {
    var owner = new AgentRunContext(UUID.randomUUID().toString(), session, dir, "p");
    turns.start(owner, "fact");
    turns.finish(owner, ConversationTurnStore.Status.COMPLETED, "agreed");
    auto.afterTerminal(owner);
  }

  /** {@code attempt} の条件を再現するため、検証用の操作や状態を扱う。 */
  void attempt() throws Exception {
    clock.advance();
    auto.tick();
    for (int i = 0; i < 200; i++) {
      Thread.sleep(5);
      if (!auto.isExecuting("s") && repository.autoState("s", "p").revision() > revision) return;
    }
  }

  /** 検証に使う改訂番号。 */
  long revision;

  /** 検証用に、依頼された操作を進めるため、試行を実行する。 */
  void runAttempt() throws Exception {
    revision = repository.autoState("s", "p").revision();
    attempt();
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    auto.close();
  }

  /** 三つタイムアウト一覧について、永続化を停止することを確認する。 */
  @Test
  void threeTimeoutsStopPersistAndNotifyOnlyOnceAndResumeWithoutModel() throws Exception {
    failure.set(new AutoSleepExecution.Failure("MODEL_TIMEOUT", null));
    assertEquals(120, auto.timeout(0).toSeconds());
    runAttempt();
    assertEquals(240, auto.timeout(repository.autoState("s", "p").stage()).toSeconds());
    runAttempt();
    assertEquals(480, auto.timeout(repository.autoState("s", "p").stage()).toSeconds());
    runAttempt();
    assertEquals(
        List.of(Duration.ofSeconds(120), Duration.ofSeconds(240), Duration.ofSeconds(480)), waits);
    assertEquals(3, calls.get());
    assertNotNull(repository.autoState("s", "p").stoppedAt());
    assertEquals(0, repository.lastProcessed("s"));
    for (int i = 0; i < 4; i++) {
      clock.advance();
      auto.tick();
    }
    assertEquals(3, calls.get());
    verify(events, times(1))
        .publish(
            eq(dev.mikoto2000.rei.event.AgentEventType.AUTO_SLEEP_STOPPED),
            eq("p"),
            eq("s"),
            isNull(),
            eq(false),
            eq(0),
            eq(0),
            eq(0),
            contains("480"));
    add("s");
    sleep.requestAutoSleep("s", "p", "shutdown");
    auto.close();
    auto = create();
    auto.afterTerminal(new AgentRunContext("r", "s", dir, "p"));
    clock.advance();
    auto.tick();
    assertEquals(3, calls.get());
    assertEquals(3, repository().autoState("s", "p").failures());
    assertTrue(auto.status("s", "p").contains("MODEL_TIMEOUT"));
    auto.resume("s", "p");
    assertEquals(3, calls.get());
    assertEquals(0, repository.autoState("s", "p").stage());
    failure.set(null);
    runAttempt();
    assertEquals(4, calls.get());
    assertEquals(2, repository.lastProcessed("s"));
  }

  /** 長期記憶の活用で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void nonTimeoutAndSuccessMaintainThenResetStage() throws Exception {
    failure.set(new AutoSleepExecution.Failure("MODEL_TIMEOUT", null));
    runAttempt();
    failure.set(new AutoSleepExecution.Failure("MODEL_REQUEST_FAILED", null));
    runAttempt();
    assertEquals(1, repository.autoState("s", "p").stage());
    failure.set(null);
    runAttempt();
    var state = repository.autoState("s", "p");
    assertEquals(0, state.failures());
    assertEquals(0, state.stage());
    assertEquals(1, repository.lastProcessed("s"));
  }

  /** 長期記憶の活用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationBudgetAndZeroPendingDoNotResetFailures() throws Exception {
    failure.set(new AutoSleepExecution.Failure("MODEL_TIMEOUT", null));
    runAttempt();
    failure.set(new java.util.concurrent.CancellationException());
    clock.advance();
    auto.tick();
    auto.close();
    assertEquals(1, repository.autoState("s", "p").failures());
    auto = create();
    auto.afterTerminal(new AgentRunContext("r", "s", dir, "p"));
    failure.set(
        new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
            dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
                .LLM_CALL_BUDGET_EXCEEDED));
    clock.advance();
    auto.tick();
    auto.close();
    assertEquals(1, repository.autoState("s", "p").failures());
    failure.set(null);
    sleep.sleep("s", "p", false);
    assertEquals(1, repository.autoState("s", "p").failures());
    sleep.sleep("s", "p", true);
    assertEquals(1, repository.autoState("s", "p").stage());
    auto = create();
    auto.afterTerminal(new AgentRunContext("r", "s", dir, "p"));
    clock.advance();
    auto.tick();
    assertEquals(1, repository.autoState("s", "p").failures());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleRevisionAndOtherProjectCannotOverwrite() {
    var old = repository.autoState("s", "p");
    var changed =
        repository.updateAutoState(old, "MODEL_TIMEOUT", "safe", clock.instant().toString(), 3);
    assertThrows(
        IllegalStateException.class, () -> repository.updateAutoState(old, null, null, null, 3));
    assertThrows(IllegalArgumentException.class, () -> repository.autoState("s", "other"));
    assertEquals(changed, repository.autoState("s", "p"));
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void stoppedSessionDoesNotBlockAnother() throws Exception {
    var state = repository.autoState("s", "p");
    for (int i = 0; i < 3; i++)
      state = repository.updateAutoState(state, "UNKNOWN", "safe", clock.instant().toString(), 3);
    add("other");
    clock.advance();
    auto.tick();
    for (int i = 0; i < 200 && repository.lastProcessed("other") == 0; i++) Thread.sleep(5);
    auto.close();
    assertEquals(1, calls.get());
    assertEquals(1, repository.lastProcessed("other"));
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void elapsedLimitClampsWaitAndHasSeparateCode() {
    var ticks = new AtomicLong();
    var execution =
        new AutoSleepExecution(Duration.ofSeconds(480), Duration.ofSeconds(600), ticks::get);
    assertEquals(Duration.ofSeconds(480), execution.waitTime());
    ticks.set(Duration.ofSeconds(590).toNanos());
    assertEquals(Duration.ofSeconds(10), execution.waitTime());
    assertTrue(execution.waitLimit().deadline());
    ticks.set(Duration.ofSeconds(600).toNanos());
    assertEquals(
        "AUTO_SLEEP_DEADLINE_EXCEEDED",
        assertThrows(AutoSleepExecution.Failure.class, execution::remaining).code);
  }

  /** 状態について、失敗情報決して行わない開始するモデルを読み取ることを確認する。 */
  @Test
  void stateReadFailureNeverStartsModelAndSuccessWriteFailureDoesNotReapply() throws Exception {
    auto.close();
    var guarded = spy(sleep);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
        .when(guarded)
        .autoState("s", "p");
    auto =
        new AutoSleepService(
            guarded,
            memory,
            config,
            new DefaultAgentActivityTracker(
                Clock.fixed(clock.instant().minusSeconds(600), ZoneOffset.UTC)),
            clock);
    auto.afterTerminal(new AgentRunContext("r", "s", dir, "p"));
    clock.advance();
    auto.tick();
    assertEquals(0, calls.get());
    auto.close();
    doCallRealMethod().when(guarded).autoState("s", "p");
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
        .when(guarded)
        .updateAutoState(any(), isNull(), isNull(), anyString(), anyInt());
    auto =
        new AutoSleepService(
            guarded,
            memory,
            config,
            new DefaultAgentActivityTracker(
                Clock.fixed(clock.instant().minusSeconds(600), ZoneOffset.UTC)),
            clock);
    auto.afterTerminal(new AgentRunContext("r", "s", dir, "p"));
    clock.advance();
    auto.tick();
    for (int i = 0; i < 200 && (repository.lastProcessed("s") == 0 || auto.isExecuting("s")); i++)
      Thread.sleep(5);
    assertEquals(1, repository.lastProcessed("s"));
    add("s");
    clock.advance();
    auto.tick();
    assertEquals(1, calls.get());
    doCallRealMethod()
        .when(guarded)
        .updateAutoState(any(), isNull(), isNull(), anyString(), anyInt());
    for (int i = 0; i < 200 && repository.lastProcessed("s") < 2; i++) {
      clock.advance();
      auto.tick();
      Thread.sleep(5);
    }
    assertEquals(2, calls.get());
    assertEquals(2, repository.lastProcessed("s"));
  }

  /** 長期記憶の活用で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void newInputDuringExtendedExecutionLeavesCheckpointAndCountersUnchanged() throws Exception {
    auto.close();
    var activity =
        new DefaultAgentActivityTracker(
            Clock.fixed(clock.instant().minusSeconds(600), ZoneOffset.UTC));
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var controlled =
        new SleepService(
            repository,
            turns,
            batch -> {
              entered.countDown();
              try {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return List.of();
            },
            new MemoryResolver((c, e) -> null, memory),
            memory);
    var state = repository.autoState("s", "p");
    repository.updateAutoState(state, "MODEL_TIMEOUT", "safe", clock.instant().toString(), 3);
    auto = new AutoSleepService(controlled, memory, config, activity, clock);
    auto.afterTerminal(new AgentRunContext("r", "s", dir, "p"));
    clock.advance();
    auto.tick();
    assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertThrows(IllegalStateException.class, () -> auto.resume("s", "p"));
    activity.recordUserActivity(clock.instant());
    auto.tick();
    release.countDown();
    auto.close();
    assertEquals(0, repository.lastProcessed("s"));
    assertEquals(1, repository.autoState("s", "p").failures());
    assertEquals(1, repository.autoState("s", "p").stage());
  }

  /** 長期記憶の活用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void overallWatchdogInterruptsNonStreamWorkWithoutAdvancingProgress() {
    var controlled =
        new SleepService(
            repository,
            turns,
            batch -> {
              try {
                new java.util.concurrent.CountDownLatch(1).await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
              }
              return List.of();
            },
            new MemoryResolver((c, e) -> null, memory),
            memory);
    var error =
        assertThrows(
            AutoSleepExecution.Failure.class,
            () ->
                controlled.sleep(
                    "s",
                    "p",
                    false,
                    () -> false,
                    new AutoSleepExecution(Duration.ofSeconds(480), Duration.ofMillis(30))));
    assertEquals("AUTO_SLEEP_DEADLINE_EXCEEDED", error.code);
    assertFalse(Thread.currentThread().isInterrupted());
    assertEquals(0, repository.lastProcessed("s"));
    assertEquals("FAILED", repository.history("p", 10).getFirst().status());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledResumeResetsStateButNeverRunsModel() {
    var state = repository.autoState("s", "p");
    for (int i = 0; i < 3; i++)
      state = repository.updateAutoState(state, "UNKNOWN", "safe", clock.instant().toString(), 3);
    auto.close();
    auto =
        new AutoSleepService(
            sleep,
            memory,
            new AutoSleepProperties(false, null, 0, null),
            new DefaultAgentActivityTracker(clock),
            clock);
    assertTrue(auto.resume("s", "p").contains("無効"));
    assertEquals(0, repository.autoState("s", "p").failures());
    clock.advance();
    auto.tick();
    assertEquals(0, calls.get());
  }

  /** 長期記憶の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void persistenceFailureRollsBackCheckpointAndCountsWithoutAdvancingStage() throws Exception {
    auto.close();
    var guarded = spy(repository);
    doAnswer(
            a -> {
              SleepRun run = a.getArgument(0);
              if (run.status().equals("COMPLETED"))
                throw new org.springframework.dao.DataAccessResourceFailureException("save failed");
              a.callRealMethod();
              return null;
            })
        .when(guarded)
        .saveRun(any());
    sleep =
        new SleepService(
            guarded, turns, batch -> List.of(), new MemoryResolver((c, e) -> null, memory), memory);
    auto = create();
    auto.afterTerminal(new AgentRunContext("r", "s", dir, "p"));
    runAttempt();
    var state = repository.autoState("s", "p");
    assertEquals(1, state.failures());
    assertEquals(0, state.stage());
    assertEquals("PERSISTENCE_FAILED", state.code());
    assertEquals(0, repository.lastProcessed("s"));
  }

  /** 長期記憶の活用で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void overallFailureCountsWithoutIncreasingTimeoutStage() throws Exception {
    failure.set(new AutoSleepExecution.Failure("AUTO_SLEEP_DEADLINE_EXCEEDED", null));
    runAttempt();
    assertEquals(1, repository.autoState("s", "p").failures());
    assertEquals(0, repository.autoState("s", "p").stage());
  }

  /** 長期記憶の活用で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void outputValidationIsCountedAndUnclassifiedIllegalStateIsNotTimeout() throws Exception {
    failure.set(new dev.mikoto2000.rei.subagent.SubAgentValidationException(List.of()));
    runAttempt();
    assertEquals("OUTPUT_INVALID", repository.autoState("s", "p").code());
    failure.set(new IllegalStateException("Timeout after 120 seconds"));
    runAttempt();
    assertEquals("UNKNOWN", repository.autoState("s", "p").code());
    assertEquals(0, repository.autoState("s", "p").stage());
  }

  /** 検証条件を再現するための変更可能な時刻の取得元。 */
  static class MutableClock extends Clock {
    /** 日時や期限の条件を再現するために使う検証時刻。 */
    Instant now = Instant.parse("2026-10-12T00:00:00Z");

    /** {@code advance} の条件を再現するため、検証用の操作や状態を扱う。 */
    void advance() {
      now = now.plusSeconds(10);
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return now;
    }

    /** 検証用に、長期記憶の活用で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
