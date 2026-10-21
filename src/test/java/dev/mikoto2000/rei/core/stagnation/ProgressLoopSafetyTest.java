package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 進捗繰り返し安全性の結果と状態の契約を検証するテスト。 */
class ProgressLoopSafetyTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code execution} の条件を再現するための補助情報を返す。 */
  RunExecutionContext execution(OutputLimitRunBudget budget) {
    return new RunExecutionContext(
        "run",
        budget,
        new ProgressEvaluator(root),
        new AgentEventFactory(java.time.Clock.systemUTC()),
        e -> {});
  }

  /** {@code iterate} の条件を再現するため、検証用の操作や状態を扱う。 */
  void iterate(RunExecutionContext e, String value) {
    e.beginIteration();
    e.recordTool(
        "fetchUrlContent",
        "{}",
        "{\"content\":\"" + value + "\"}",
        e.evaluator().beforeTool("fetchUrlContent", "{}"));
    e.endIteration();
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownDoesNotBecomeProgressOrImmediateStagnationButEventuallyReplans() {
    var e = execution(new OutputLimitRunBudget(2, 100));
    e.evaluator().restoreInformation(null);
    for (int i = 0; i < 4; i++) iterate(e, "unknown" + i);
    assertThat(e.progressVersion()).isZero();
    assertThat(e.detector().isStagnant()).isFalse();
    for (int i = 0; i < 20; i++) iterate(e, "unknown" + i);
    assertThat(e.detector().isStagnant()).isFalse();
    assertThat(e.requestReplan()).isNotNull();
  }

  /** 実行の停滞への対処で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void outputSplitsDoNotConsumeStagnationBudget() {
    var budget = new OutputLimitRunBudget(1, 100);
    assertThat(budget.tryConsumeReplan()).isTrue();
    var e = execution(budget);
    for (int i = 0; i < 4; i++) {
      e.beginIteration();
      e.endIteration();
    }
    assertThat(e.requestReplan()).isNotNull();
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void acquisitionCannotHideStalledWorkForever() {
    var e = execution(new OutputLimitRunBudget(2, 100));
    for (int i = 0; i < 40; i++) iterate(e, "fact" + i);
    assertThat(e.detector().isStagnant()).isTrue();
    assertThat(e.requestReplan()).isNotNull();
  }

  /** 実行の停滞への対処で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void unknownRunStopsWithinCommonBudgetAndCannotLoopForever() {
    var e = execution(new OutputLimitRunBudget(2, 100));
    e.evaluator().restoreInformation(null);
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 1000; i++) {
                e.consumeNextLlmCall();
                iterate(e, "unknown" + i);
                e.requestReplan();
              }
            })
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
  }

  /** 実行の停滞への対処で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void unknownRunStopsAfterDiagnosisWhenCallLimitIsDisabled() {
    var budget = new OutputLimitRunBudget(2, 1, null, 0, 5, false);
    var e = execution(budget);
    e.evaluator().restoreInformation(null);
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 1000; i++) {
                e.consumeNextLlmCall();
                iterate(e, "unknown" + i);
                e.requestReplan();
              }
            })
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("STAGNATED");
    assertThat(budget.snapshot().llmCalls()).isEqualTo(48);
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void acquiredInformationDoesNotCompletePlanAndConfirmedWorkResetsWorkStall() {
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    plan.addStep("review document");
    var e =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 100),
            new ProgressEvaluator(root, plan),
            new AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    for (int i = 0; i < 30; i++) iterate(e, "fact" + i);
    assertThat(plan.steps())
        .allSatisfy(
            step ->
                assertThat(step.status())
                    .isNotEqualTo(dev.mikoto2000.rei.core.actionplan.ActionPlan.STATUS_DONE));
    e.completeSubgoal("review document");
    for (int i = 30; i < 60; i++) iterate(e, "fact" + i);
    assertThat(e.detector().isStagnant()).isFalse();
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void legacyWaitingContinuationWithoutLedgerDoesNotAssumeUnreadInformation() {
    var e = execution(new OutputLimitRunBudget(2, 100));
    e.resume(
        new dev.mikoto2000.rei.core.chat.RunContinuation.Saved(
            "request", java.util.List.of(), new OutputLimitRunBudget.Snapshot(0, 4, 0, false)));
    iterate(e, "historical fact");
    assertThat(e.progressVersion()).isZero();
  }
}
