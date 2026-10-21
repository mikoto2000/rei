package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Flux;

/** 補助エージェント共有実行予算の結果と状態の契約を検証するテスト。 */
class SubAgentSharedRunBudgetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** {@code fixture} の条件を再現するための補助情報を返す。 */
  SubAgentRunnerTest fixture() {
    var result = new SubAgentRunnerTest();
    result.directory = directory;
    return result;
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  RunExecutionContext context(OutputLimitRunBudget budget) {
    return new RunExecutionContext("parent", budget, null, null, null);
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void normalChatParentAndChildConsumeOneLocalBudgetWithoutGoal() throws Exception {
    var fixture = fixture();
    var calls = new AtomicInteger();
    var child =
        fixture.runner(
            prompt -> {
              calls.incrementAndGet();
              return Flux.just(fixture.answer(SubAgentResultParserTest.VALID));
            },
            "2s");
    var budget = new OutputLimitRunBudget(0, 2);
    var execution = context(budget);
    execution.consumeNextLlmCall();
    var tools = new SubAgentTools(child, null);
    var toolContext = new ToolContext(Map.of(RunExecutionContext.KEY, execution));
    assertThat(tools.delegateTask("reviewer", "inspect", null, toolContext).status())
        .isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(budget.remainingLlmCalls()).isZero();
    var stopped = tools.delegateTask("reviewer", "inspect", null, toolContext);
    assertThat(stopped.status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(stopped.output()).contains("SHARED_LLM_BUDGET_EXHAUSTED");
    assertThat(calls).hasValue(1);
  }

  /** 補助エージェントへの委譲で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void parallelNormalChatChildrenCompeteForOneRemainingLocalCall() throws Exception {
    var fixture = fixture();
    var calls = new AtomicInteger();
    var child =
        fixture.runner(
            prompt -> {
              calls.incrementAndGet();
              return Flux.just(fixture.answer(SubAgentResultParserTest.VALID));
            },
            "2s");
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(fixture.policy, model -> true));
    registry.reload();
    var budget = new OutputLimitRunBudget(0, 1);
    var execution = context(budget);
    try (var parallel = new ParallelSubAgentDelegator(child, registry, fixture.cancellation)) {
      var tools = new SubAgentTools(child, registry);
      tools.parallelDelegator(parallel);
      var batch =
          tools.delegateTasks(
              List.of(
                  new ParallelSubAgentDelegator.Request("one", "reviewer", "one", null),
                  new ParallelSubAgentDelegator.Request("two", "reviewer", "two", null)),
              new ToolContext(Map.of(RunExecutionContext.KEY, execution)));
      assertThat(batch.status()).isEqualTo(ParallelSubAgentDelegator.Status.PARTIAL);
      assertThat(calls).hasValue(1);
      assertThat(budget.remainingLlmCalls()).isZero();
    }
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void sharedGoalReservationIsChargedOnceAndCannotBypassLocalLimit() {
    var charged = new AtomicInteger();
    var goal =
        new OutputLimitRunBudget.LlmCallReservation() {
          /** {@code tryReserve} の条件を再現するための補助情報を返す。 */
          public boolean tryReserve() {
            charged.incrementAndGet();
            return true;
          }

          /** {@code remaining} の条件を再現するための補助情報を返す。 */
          public int remaining() {
            return 10;
          }
        };
    var execution = context(new OutputLimitRunBudget(0, 1, goal));
    var child = execution.sharedLlmReservation();
    assertThat(child.tryReserve()).isTrue();
    assertThat(child.tryReserve()).isFalse();
    assertThat(child.remaining()).isZero();
    assertThat(charged).hasValue(1);
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void independentSemanticJudgeAlsoConsumesNormalChatBudget() throws Exception {
    for (int limit : List.of(1, 2)) {
      var fixture = fixture();
      fixture.evidenceValidation = true;
      fixture.maxSteps = 4;
      fixture.requiredCallsConfiguration = "semanticValidation: true\n";
      var calls = new AtomicInteger();
      var child =
          fixture.runner(
              prompt -> {
                calls.incrementAndGet();
                return Flux.just(
                    fixture.answer(
                        prompt
                                .getInstructions()
                                .getFirst()
                                .getText()
                                .contains("independent semantic validator")
                            ? "{\"valid\":true,\"issues\":[]}"
                            : "{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}"));
              },
              "2s");
      var budget = new OutputLimitRunBudget(0, limit);
      var execution = context(budget);
      var result =
          new SubAgentTools(child, null)
              .delegateTask(
                  "reviewer",
                  "inspect",
                  null,
                  new ToolContext(Map.of(RunExecutionContext.KEY, execution)));
      assertThat(result.status())
          .isEqualTo(limit == 1 ? SubAgentResult.Status.FAILED : SubAgentResult.Status.COMPLETED);
      assertThat(calls).hasValue(limit);
      assertThat(budget.remainingLlmCalls()).isZero();
    }
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledParentStopsChildBeforeModelAndDoesNotConsumeBudget() throws Exception {
    var fixture = fixture();
    var calls = new AtomicInteger();
    var child =
        fixture.runner(
            prompt -> {
              calls.incrementAndGet();
              return Flux.just(fixture.answer(SubAgentResultParserTest.VALID));
            },
            "2s");
    var budget = new OutputLimitRunBudget(0, 1);
    var execution = context(budget);
    execution.cancel();
    var result =
        new SubAgentTools(child, null)
            .delegateTask(
                "reviewer",
                "inspect",
                null,
                new ToolContext(Map.of(RunExecutionContext.KEY, execution)));
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.CANCELLED);
    assertThat(calls).hasValue(0);
    assertThat(budget.remainingLlmCalls()).isEqualTo(1);
  }
}
