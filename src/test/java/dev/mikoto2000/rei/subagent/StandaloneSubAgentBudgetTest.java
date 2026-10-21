package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** 独立した補助エージェント予算の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class StandaloneSubAgentBudgetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う判断基準。 */
  final SubAgentToolPolicy policy = new SubAgentToolPolicy(Set.of("readMultiFile"));

  /** 検証に使うキャンセル。 */
  final CommandCancellationService cancellation = new CommandCancellationService();

  /** 検証に使う登録台帳。 */
  SubAgentRegistry registry;

  /** 検証で使用する実行の担当を、指定された条件に合わせて用意する。 */
  SubAgentRunner runner(
      java.util.function.Function<Prompt, Flux<ChatResponse>> responses, String extra)
      throws Exception {
    Files.writeString(
        directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer") + extra);
    registry = new SubAgentRegistry(directory, new SubAgentDefinitionLoader(policy, m -> true));
    registry.reload();
    var model =
        new ChatModel() {
          /** 検証用に、補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError("streaming required");
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            return responses.apply(prompt);
          }
        };
    var tool =
        new ToolCallback() {
          /** 検証用に、補助エージェントへの委譲で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readMultiFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            return "observed";
          }
        };
    return new SubAgentRunner(
        registry,
        policy,
        m -> model,
        m -> ToolCallingChatOptions.builder().build(),
        () -> List.of(tool),
        cancellation,
        new AgentEventFactory(Clock.systemUTC()),
        e -> {},
        Clock.systemUTC());
  }

  /** {@code answer} の条件を再現するための補助情報を返す。 */
  ChatResponse answer(String content, Integer tokens) {
    var metadata =
        tokens == null
            ? ChatResponseMetadata.builder().build()
            : ChatResponseMetadata.builder().usage(new DefaultUsage(tokens, 0)).build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
  }

  /** {@code limits} の条件を再現するための補助情報を返す。 */
  SubAgentProperties limits(int calls, long tokens) {
    var properties = new SubAgentProperties();
    properties.setStandaloneMaxLlmCalls(calls);
    properties.setStandaloneMaxTotalTokens(tokens);
    return properties;
  }

  /** {@code requests} の条件を再現するための補助情報を返す。 */
  List<ParallelSubAgentDelegator.Request> requests(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(i -> new ParallelSubAgentDelegator.Request("item-" + i, "reviewer", "task", null))
        .toList();
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void directUnknownUsageCannotCompleteButEachInvocationHasItsOwnBudget() throws Exception {
    var calls = new AtomicInteger();
    var runner =
        runner(
            p -> {
              calls.incrementAndGet();
              return Flux.just(answer(SubAgentResultParserTest.VALID, null));
            },
            "");
    runner.setStandaloneBudgetProperties(limits(2, 5));
    for (int i = 0; i < 2; i++) {
      var result = runner.run("reviewer", "task", null);
      assertEquals(SubAgentResult.Status.FAILED, result.status());
      assertTrue(result.output().contains("TOKEN_USAGE_UNKNOWN"));
    }
    assertEquals(2, calls.get());
  }

  /** 補助エージェントへの委譲で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void parallelBatchSharesOneCallAllowanceAcrossAllChildren() throws Exception {
    var calls = new AtomicInteger();
    var runner =
        runner(
            p -> {
              calls.incrementAndGet();
              return Flux.just(answer(SubAgentResultParserTest.VALID, 2));
            },
            "");
    runner.setStandaloneBudgetProperties(limits(8, 0));
    try (var delegator =
        new ParallelSubAgentDelegator(runner, registry, cancellation, Duration.ofSeconds(3))) {
      delegator.setStandaloneBudgetProperties(limits(1, 0));
      var result = delegator.delegate(requests(4));
      assertEquals(ParallelSubAgentDelegator.Status.PARTIAL, result.status());
      assertEquals(
          1,
          result.items().stream()
              .filter(i -> i.status() == ParallelSubAgentDelegator.Status.COMPLETED)
              .count());
      assertEquals(1, calls.get());
      assertEquals(
          List.of("item-0", "item-1", "item-2", "item-3"),
          result.items().stream().map(ParallelSubAgentDelegator.Item::id).toList());
      assertEquals(
          ParallelSubAgentDelegator.Status.COMPLETED, delegator.delegate(requests(1)).status());
      assertEquals(2, calls.get());
    }
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void partiallySettledTokenWaveCannotAdmitAnotherChild() {
    var budget = StandaloneSubAgentBudget.create(limits(10, 5));
    assertTrue(budget.tryReserve());
    assertTrue(budget.tryReserve());
    budget.recordTotalTokens(3);
    assertFalse(budget.tryReserve(), "An outstanding sibling may consume the remaining tokens");
    assertThrows(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,
        () -> budget.recordTotalTokens(3));
    assertEquals(0, budget.remaining());
  }

  /** 補助エージェントへの委譲で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void parallelInFlightUsageIsChargedAndLaterChildrenCannotBypassOvershoot() throws Exception {
    var calls = new AtomicInteger();
    var entered = new java.util.concurrent.CountDownLatch(2);
    var runner =
        runner(
            p -> {
              calls.incrementAndGet();
              entered.countDown();
              try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                throw new java.util.concurrent.CancellationException();
              }
              return Flux.just(answer(SubAgentResultParserTest.VALID, 3));
            },
            "");
    try (var delegator =
        new ParallelSubAgentDelegator(runner, registry, cancellation, Duration.ofSeconds(4))) {
      delegator.setStandaloneBudgetProperties(limits(10, 5));
      var result = delegator.delegate(requests(4));
      assertEquals(2, calls.get());
      assertTrue(
          result.items().stream()
              .anyMatch(
                  i ->
                      i.result() != null && i.result().output().contains("TOKEN_BUDGET_EXCEEDED")));
      assertTrue(
          result.items().stream()
              .noneMatch(
                  i ->
                      i.status() == ParallelSubAgentDelegator.Status.COMPLETED
                          && i.id().matches("item-[23]")));
    }
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void repairAndIndependentSemanticJudgeShareTheSameStandaloneReservation() throws Exception {
    var calls = new AtomicInteger();
    var runner =
        runner(
            p -> {
              calls.incrementAndGet();
              return Flux.just(answer("{}", 2));
            },
            "maxRepairs: 1\n");
    runner.setStandaloneBudgetProperties(limits(1, 0));
    var result = runner.run("reviewer", "task", null);
    assertEquals(SubAgentResult.Status.FAILED, result.status());
    assertEquals(1, calls.get());
    assertEquals(1, result.repairAttempts());
    assertFalse(result.validationHistory().isEmpty());
    calls.set(0);
    runner =
        runner(
            p -> {
              calls.incrementAndGet();
              return Flux.just(
                  answer(
                      "{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}",
                      5));
            },
            "evidenceTools: [readMultiFile]\nsemanticValidation: true\n");
    runner.setStandaloneBudgetProperties(limits(0, 5));
    result = runner.run("reviewer", "task", null);
    assertEquals(SubAgentResult.Status.FAILED, result.status());
    assertTrue(result.output().contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(1, calls.get());
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void overshootStopsBeforeValidationAndExplicitParentReservationTakesPriority() throws Exception {
    var calls = new AtomicInteger();
    var runner =
        runner(
            p -> {
              calls.incrementAndGet();
              return Flux.just(answer(SubAgentResultParserTest.VALID, 6));
            },
            "");
    runner.setStandaloneBudgetProperties(limits(1, 5));
    var failed = runner.run("reviewer", "task", null);
    assertEquals(SubAgentResult.Status.FAILED, failed.status());
    assertNull(failed.structuredOutput());
    assertTrue(failed.output().contains("TOKEN_BUDGET_EXCEEDED"));
    var parent =
        new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation() {
          /** 検証に使う残量。 */
          final AtomicInteger remaining = new AtomicInteger(1);

          /** {@code tryReserve} の条件を再現するための補助情報を返す。 */
          public boolean tryReserve() {
            return remaining.getAndDecrement() > 0;
          }

          /** {@code remaining} の条件を再現するための補助情報を返す。 */
          public int remaining() {
            return remaining.get();
          }
        };
    assertEquals(
        SubAgentResult.Status.COMPLETED, runner.run("reviewer", "task", null, parent).status());
    assertEquals(2, calls.get());
  }

  /** 提供元失敗情報および不明使用状況について、共有まとめた処理終了済みを維持することを確認する。 */
  @Test
  void providerFailureAndUnknownUsageKeepSharedBatchClosed() throws Exception {
    for (boolean failed : List.of(false, true)) {
      var calls = new AtomicInteger();
      var runner =
          runner(
              p -> {
                calls.incrementAndGet();
                return failed
                    ? Flux.error(new IllegalStateException("private provider detail"))
                    : Flux.just(answer(SubAgentResultParserTest.VALID, 0));
              },
              "");
      try (var delegator =
          new ParallelSubAgentDelegator(runner, registry, cancellation, Duration.ofSeconds(3))) {
        delegator.setStandaloneBudgetProperties(limits(1, 5));
        var result = delegator.delegate(requests(4));
        assertEquals(ParallelSubAgentDelegator.Status.FAILED, result.status());
        assertEquals(1, calls.get());
        assertTrue(
            result.items().stream()
                .anyMatch(
                    i ->
                        i.result() != null && i.result().output().contains("TOKEN_USAGE_UNKNOWN")));
        assertFalse(result.toString().contains("private provider detail"));
      }
    }
  }

  /** 旧形式作業習慣を保持することを確認する。 */
  @Test
  void disabledSettingsAndInvalidPreflightPreserveLegacyBehavior() throws Exception {
    var calls = new AtomicInteger();
    var runner =
        runner(
            p -> {
              calls.incrementAndGet();
              return Flux.just(answer(SubAgentResultParserTest.VALID, null));
            },
            "");
    assertEquals(SubAgentResult.Status.COMPLETED, runner.run("reviewer", "task", null).status());
    runner.setStandaloneBudgetProperties(limits(1, 1));
    assertEquals(SubAgentResult.Status.UNKNOWN_AGENT, runner.run("missing", "task", null).status());
    assertEquals(1, calls.get());
    try (var delegator =
        new ParallelSubAgentDelegator(runner, registry, cancellation, Duration.ofSeconds(3))) {
      delegator.setStandaloneBudgetProperties(limits(1, 1));
      assertThrows(IllegalArgumentException.class, () -> delegator.delegate(requests(9)));
    }
    assertEquals(1, calls.get());
  }

  /** 不正な上限一覧を受け付けないことを確認する。 */
  @Test
  void settingBindingAndAtomicReservationsRejectInvalidLimits() throws Exception {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "rei.subagents.standalone-max-llm-calls",
                "3",
                "rei.subagents.standalone-max-total-tokens",
                "5"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.subagents",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    SubAgentProperties.class))
            .get();
    assertEquals(3, properties.getStandaloneMaxLlmCalls());
    assertEquals(5, properties.getStandaloneMaxTotalTokens());
    assertThrows(IllegalArgumentException.class, () -> properties.setStandaloneMaxLlmCalls(1001));
    assertThrows(IllegalArgumentException.class, () -> properties.setStandaloneMaxTotalTokens(-1));
    var budget = StandaloneSubAgentBudget.create(limits(3, 5));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 8; i++) jobs.add(pool.submit(budget::tryReserve));
      int accepted = 0;
      for (var job : jobs) if (job.get()) accepted++;
      assertEquals(3, accepted);
    }
    budget.recordTotalTokens(3);
    assertThrows(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,
        () -> budget.recordTotalTokens(3));
    assertEquals(0, budget.remaining());
    assertTrue(budget.tokenExhausted());
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void timedOutProviderLeavesSharedUsageUnknownBeforeNextReservation() throws Exception {
    var fixture = new SubAgentRunnerTest();
    fixture.directory = directory;
    var disposed = new java.util.concurrent.CountDownLatch(1);
    var runner =
        fixture.runner(p -> Flux.<ChatResponse>never().doOnCancel(disposed::countDown), "100ms");
    var budget = StandaloneSubAgentBudget.create(limits(2, 5));
    assertEquals(
        SubAgentResult.Status.TIMEOUT, runner.run("reviewer", "task", null, budget).status());
    assertTrue(disposed.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(budget.usageUnknown());
    assertEquals(0, budget.remaining());
    assertThrows(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class, budget::tryReserve);
  }
}
