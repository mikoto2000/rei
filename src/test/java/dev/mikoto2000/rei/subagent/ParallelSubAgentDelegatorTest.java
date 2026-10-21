package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 並列補助エージェントDelegatorの結果と状態の契約を検証するテスト。 */
class ParallelSubAgentDelegatorTest {
  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void dagFailFastCancelsSlowSiblingEvenWhenItPrecedesFailure() throws Exception {
    var interrupted = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    try (var delegator =
        new ParallelSubAgentDelegator(
            mock(SubAgentRunner.class),
            registry(),
            new CommandCancellationService(),
            Duration.ofSeconds(3))) {
      long begin = System.nanoTime();
      var batch =
          delegator.delegate(
              requests(2),
              null,
              (request, budget) -> {
                if (request.id().equals("item-0")) {
                  started.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException e) {
                    interrupted.countDown();
                  }
                  return completed();
                }
                try {
                  assertTrue(started.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                return new SubAgentResult(
                    "reviewer",
                    "failed",
                    SubAgentResult.Status.FAILED,
                    "invalid result",
                    Instant.now(),
                    Instant.now());
              },
              Duration.ofSeconds(3),
              true);
      assertEquals(ParallelSubAgentDelegator.Status.FAILED, batch.status());
      assertTrue(interrupted.await(1, TimeUnit.SECONDS));
      assertTrue(System.nanoTime() - begin < Duration.ofSeconds(2).toNanos());
    }
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** {@code registry} の条件を再現するための補助情報を返す。 */
  SubAgentRegistry registry() throws Exception {
    Files.writeString(
        directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer"));
    var registry =
        new SubAgentRegistry(
            directory,
            new SubAgentDefinitionLoader(
                new SubAgentToolPolicy(Set.of("readMultiFile")), m -> true));
    registry.reload();
    return registry;
  }

  /** {@code requests} の条件を再現するための補助情報を返す。 */
  List<ParallelSubAgentDelegator.Request> requests(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(
            i -> new ParallelSubAgentDelegator.Request("item-" + i, "reviewer", "task-" + i, null))
        .toList();
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void blankOptionalFieldsDispatchLocally() throws Exception {
    var runner = mock(SubAgentRunner.class);
    when(runner.run("reviewer", "review", null)).thenReturn(completed());
    try (var delegator =
            new ParallelSubAgentDelegator(
                runner, registry(), new CommandCancellationService(), Duration.ofSeconds(1));
        var scope =
            AgentRunScope.open(new AgentRunContext("parent", "session", directory, "project"))) {
      var request =
          new ParallelSubAgentDelegator.Request(
              "review", "reviewer", "review", " ", "", " ", "", List.of(), "");
      assertEquals(
          ParallelSubAgentDelegator.Status.COMPLETED,
          delegator.delegate(List.of(request)).status());
      verify(runner).run("reviewer", "review", null);
      verify(runner, never()).runAutomaticBackend(any(), any(), any());
    }
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void singleDelegationAlsoTreatsBlankOptionsAsOmitted() {
    var runner = mock(SubAgentRunner.class);
    when(runner.run("reviewer", "review", null)).thenReturn(completed());
    var tools = new SubAgentTools(runner, null);
    assertInstanceOf(
        SubAgentResult.class,
        tools.delegateTask(
            "reviewer", "review", " ", "", " ", "", List.of(), List.of(), List.of(), "", null));
    verify(runner).run("reviewer", "review", null);
    verify(runner, never()).runAutomaticBackend(any(), any(), any());
  }

  /** {@code completed} の条件を再現するための補助情報を返す。 */
  SubAgentResult completed() {
    return new SubAgentResult(
        "reviewer",
        UUID.randomUUID().toString(),
        SubAgentResult.Status.COMPLETED,
        "done",
        Instant.now(),
        Instant.now());
  }

  /** {@code externalRequests} の条件を再現するための補助情報を返す。 */
  List<ParallelSubAgentDelegator.Request> externalRequests(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(
            i ->
                new ParallelSubAgentDelegator.Request(
                    "item-" + i,
                    "reviewer",
                    "task-" + i,
                    null,
                    "chatgpt-plan",
                    "default",
                    "allowed-model",
                    List.of("docs/source.md"),
                    "{\"findings\":[]}"))
        .toList();
  }

  /** {@code externalSettings} の条件を再現するための補助情報を返す。 */
  dev.mikoto2000.rei.chatgpt.PlanExecutionProperties externalSettings(Duration timeout) {
    return new dev.mikoto2000.rei.chatgpt.PlanExecutionProperties(
        true, null, null, null, null, null, timeout, false, null);
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void externalBatchUsesConfiguredThirtyMinuteDeadlineAndLocalDagLimitsStayBounded()
      throws Exception {
    try (var delegator =
        new ParallelSubAgentDelegator(
            mock(SubAgentRunner.class), registry(), new CommandCancellationService())) {
      assertEquals(Duration.ofSeconds(120), delegator.batchDeadline(requests(1)));
      assertEquals(Duration.ofMinutes(10), delegator.batchDeadline(externalRequests(1)));
      delegator.setPlanExecutionProperties(externalSettings(Duration.ofMinutes(30)));
      assertEquals(Duration.ofMinutes(30), delegator.batchDeadline(externalRequests(1)));
      assertEquals(Duration.ofSeconds(120), delegator.batchDeadline(requests(1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              delegator.delegate(requests(1), null, (r, b) -> completed(), Duration.ofMinutes(30)));
    }
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void externalCallCanOutliveTheLocalBatchLimitWithoutWaitingMinutes() throws Exception {
    var runner = mock(SubAgentRunner.class);
    var receipt = mock(ChatGptSubAgentBackend.Receipt.class);
    when(receipt.result()).thenReturn(completed());
    when(runner.runAutomaticBackend(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(300);
              return receipt;
            });
    var budget = mock(dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation.class);
    try (var delegator =
            new ParallelSubAgentDelegator(
                runner, registry(), new CommandCancellationService(), Duration.ofMillis(100));
        var scope =
            AgentRunScope.open(new AgentRunContext("parent", "chat", directory, "project"))) {
      delegator.setPlanExecutionProperties(externalSettings(Duration.ofSeconds(2)));
      assertEquals(
          ParallelSubAgentDelegator.Status.COMPLETED,
          delegator.delegate(externalRequests(1), budget).status());
      verify(runner).runAutomaticBackend(any(), any(), same(budget));
    }
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void externalDeadlineStillCancelsWorkersAndNeverStartsQueuedChildren() throws Exception {
    var runner = mock(SubAgentRunner.class);
    var interrupted = new CountDownLatch(2);
    var calls = new AtomicInteger();
    when(runner.runAutomaticBackend(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              var receipt = mock(ChatGptSubAgentBackend.Receipt.class);
              when(receipt.result()).thenReturn(completed());
              return receipt;
            });
    var budget = mock(dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation.class);
    try (var delegator =
            new ParallelSubAgentDelegator(
                runner, registry(), new CommandCancellationService(), Duration.ofMillis(100));
        var scope =
            AgentRunScope.open(new AgentRunContext("parent", "chat", directory, "project"))) {
      delegator.setPlanExecutionProperties(externalSettings(Duration.ofMillis(300)));
      var result = delegator.delegate(externalRequests(4), budget);
      assertEquals(ParallelSubAgentDelegator.Status.TIMEOUT, result.status());
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertEquals(4, result.items().size());
    }
  }

  /** 許容範囲同時実行数について、順序を保持することを確認する。 */
  @Test
  void boundsConcurrencyPreservesOrderAndParentScope() throws Exception {
    var runner = mock(SubAgentRunner.class);
    var running = new AtomicInteger();
    var peak = new AtomicInteger();
    var started = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var parent =
        new AgentRunContext(
            "parent", "chat", directory, "project", AgentRunContext.RequestSource.WEB);
    when(runner.run(anyString(), anyString(), isNull()))
        .thenAnswer(
            invocation -> {
              assertEquals(parent, AgentRunScope.current());
              int active = running.incrementAndGet();
              peak.accumulateAndGet(active, Math::max);
              started.countDown();
              try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
                return completed();
              } finally {
                running.decrementAndGet();
              }
            });
    try (var delegator =
        new ParallelSubAgentDelegator(
            runner, registry(), new CommandCancellationService(), Duration.ofSeconds(3))) {
      var result = new AtomicReference<ParallelSubAgentDelegator.Batch>();
      var thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var scope = AgentRunScope.open(parent)) {
                      result.set(delegator.delegate(requests(4)));
                    }
                  });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertEquals(2, peak.get());
      release.countDown();
      thread.join(3000);
      assertFalse(thread.isAlive());
      assertEquals("COMPLETED", result.get().status().name());
      assertEquals(
          List.of("item-0", "item-1", "item-2", "item-3"),
          result.get().items().stream().map(ParallelSubAgentDelegator.Item::id).toList());
      assertEquals(
          4, result.get().items().stream().map(i -> i.result().subAgentRunId()).distinct().count());
    }
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void preflightRejectsWholeInvalidBatchBeforeCallingChildren() throws Exception {
    var runner = mock(SubAgentRunner.class);
    try (var delegator =
        new ParallelSubAgentDelegator(
            runner, registry(), new CommandCancellationService(), Duration.ofSeconds(1))) {
      assertThrows(IllegalArgumentException.class, () -> delegator.delegate(requests(9)));
      assertThrows(
          IllegalArgumentException.class,
          () -> delegator.delegate(List.of(requests(1).getFirst(), requests(1).getFirst())));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              delegator.delegate(
                  List.of(
                      new ParallelSubAgentDelegator.Request("missing", "unknown", "task", null))));
      var child = new AgentRunContext("child", "subagent:child", directory);
      try (var scope = AgentRunScope.open(child)) {
        assertThrows(IllegalArgumentException.class, () -> delegator.delegate(requests(1)));
      }
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(runner);
    }
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void timeoutCancelsWorkersAndDoesNotStartQueuedChildren() throws Exception {
    var runner = mock(SubAgentRunner.class);
    var interrupted = new CountDownLatch(2);
    var calls = new AtomicInteger();
    when(runner.run(anyString(), anyString(), isNull()))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              return completed();
            });
    try (var delegator =
        new ParallelSubAgentDelegator(
            runner, registry(), new CommandCancellationService(), Duration.ofMillis(200))) {
      var result = delegator.delegate(requests(4));
      assertEquals("TIMEOUT", result.status().name());
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertEquals(4, result.items().size());
    }
  }

  /** 部分的な結果失敗情報について、同時に依頼された別の操作結果一覧を保持することを確認する。 */
  @Test
  void partialFailureRetainsSiblingResultsAndDoesNotEchoException() throws Exception {
    var runner = mock(SubAgentRunner.class);
    when(runner.run("reviewer", "task-0", null)).thenReturn(completed());
    when(runner.run("reviewer", "task-1", null))
        .thenThrow(new IllegalStateException("private failure"));
    try (var delegator =
        new ParallelSubAgentDelegator(
            runner, registry(), new CommandCancellationService(), Duration.ofSeconds(1))) {
      var result = delegator.delegate(requests(2));
      assertEquals(ParallelSubAgentDelegator.Status.PARTIAL, result.status());
      assertNotNull(result.items().getFirst().result());
      assertNull(result.items().getLast().result());
      assertFalse(result.toString().contains("private failure"));
    }
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void parentCancellationCancelsPendingRequestsAndBusyBatchDoesNotDispatch() throws Exception {
    var runner = mock(SubAgentRunner.class);
    var cancellation = new CommandCancellationService();
    var started = new CountDownLatch(2);
    var interrupted = new CountDownLatch(2);
    var calls = new AtomicInteger();
    when(runner.run(anyString(), anyString(), isNull()))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              started.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              return completed();
            });
    var parent = new AgentRunContext("parent", "chat", directory);
    try (var delegator =
            new ParallelSubAgentDelegator(runner, registry(), cancellation, Duration.ofSeconds(3));
        var scope = AgentRunScope.open(parent)) {
      cancellation.begin(null);
      var result = new AtomicReference<ParallelSubAgentDelegator.Batch>();
      var thread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var child = AgentRunScope.open(parent)) {
                      result.set(delegator.delegate(requests(4)));
                    }
                  });
      try {
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(
            ParallelSubAgentDelegator.Status.REJECTED, delegator.delegate(requests(1)).status());
        cancellation.cancel();
        thread.join(2000);
        assertFalse(thread.isAlive());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals(ParallelSubAgentDelegator.Status.CANCELLED, result.get().status());
        assertEquals(2, calls.get());
        assertEquals(4, result.get().items().size());
      } finally {
        cancellation.cancel();
        cancellation.clear();
      }
    }
  }
}
