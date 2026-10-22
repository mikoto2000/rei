package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoalStagnationTest {
  @TempDir Path root;

  private RunExecutionContext context(OutputLimitRunBudget budget) {
    return new RunExecutionContext(
        "run",
        budget,
        new ProgressEvaluator(root),
        new AgentEventFactory(Clock.systemUTC()),
        event -> {});
  }

  private void read(RunExecutionContext context, int start) {
    context.beginIteration();
    context.recordTool(
        "readMultiFile",
        "{}",
        "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":" + start + ",\"content\":[\"line\"]}",
        context.evaluator().beforeTool("readMultiFile", "{}"));
    context.endIteration();
  }

  @Test
  void forwardCoverageEventuallyRequestsWorkWithoutLocalStagnation() {
    var context = context(new OutputLimitRunBudget(2, 1000));
    for (int i = 1; i <= 96; i++) read(context, i);
    assertThat(context.diagnosis().localStagnant()).isFalse();
    assertThat(context.diagnosis().goalStagnant()).isTrue();
    assertThat(context.requestReplan()).contains("COVERAGE_WITHOUT_WORK", "中間成果物");
    assertThat(context.budgetSnapshot().stagnationReplans()).isEqualTo(1);
  }

  @Test
  void verifiedFileChangesResolveGoalStallWhileNoOpWritesDoNot() throws Exception {
    var context = context(new OutputLimitRunBudget(2, 1000));
    for (int i = 1; i <= 96; i++) read(context, i);
    context.beginIteration();
    var before = context.evaluator().beforeTool("writeTextFile", "{\"path\":\"findings.md\"}");
    Files.writeString(root.resolve("findings.md"), "verified findings");
    context.recordTool("writeTextFile", "{}", "{}", before);
    context.endIteration();
    assertThat(context.diagnosis().goalStagnant()).isFalse();
    assertThat(context.diagnosis().lastProgress().category())
        .isEqualTo(ProgressEvidence.Category.WORK_PRODUCT_UPDATED);
    context.beginIteration();
    context.recordTool(
        "writeTextFile",
        "{}",
        "{}",
        context.evaluator().beforeTool("writeTextFile", "{\"path\":\"findings.md\"}"));
    context.endIteration();
    assertThat(context.diagnosis().goalIdleIterations()).isEqualTo(1);
  }

  @Test
  void recoveryIsReasonAwareBoundedAndSurvivesResumeWithSpentBudget() {
    var context = context(new OutputLimitRunBudget(2, 1000));
    read(context, 1);
    for (int i = 0; i < 4; i++) read(context, 1);
    assertThat(context.diagnosis().localStagnant()).isTrue();
    assertThat(context.requestReplan()).contains("REPEATED_READ", "未読範囲");
    var resumed = context(new OutputLimitRunBudget(2, 1000));
    resumed.restoreProgress(context.progressSnapshot());
    for (int i = 0; i < 4; i++) read(resumed, 1);
    assertThat(resumed.requestReplan()).contains("完了条件");
    for (int i = 0; i < 4; i++) read(resumed, 1);
    assertThatThrownBy(resumed::requestReplan)
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("STAGNATED")
        .hasMessageContaining("REPEATED_READ")
        .hasMessageContaining("replanBudget=2");
    assertThat(resumed.diagnosis().recoveries()).hasSize(2);
  }

  @Test
  void stalledStepAndFailedRestorationHaveSpecificRecoveryGuidance() {
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    plan.addStep("review");
    plan.startStep("step-1");
    var step =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 1000),
            new ProgressEvaluator(root, plan),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    for (int i = 0; i < 4; i++) {
      step.beginIteration();
      step.endIteration();
    }
    assertThat(step.requestReplan()).contains("STEP_STALLED", "完了条件");
    var original = context(new OutputLimitRunBudget(2, 1000));
    read(original, 1);
    var resumed = context(new OutputLimitRunBudget(2, 1000));
    resumed.restoreProgress(original.progressSnapshot());
    resumed.beginContextRestoration();
    for (int i = 0; i < 4; i++) {
      resumed.beginIteration();
      resumed.recordTool(
          "readMultiFile",
          "{}",
          "{\"error\":\"missing\"}",
          resumed.evaluator().beforeTool("readMultiFile", "{}"));
      resumed.endIteration();
    }
    assertThat(resumed.requestReplan()).contains("CONTEXT_RESTORATION_FAILED", "要約");
  }

  @Test
  void recoveryBudgetIsCheckpointedBeforeTheNextModelCallAndSubgoalsDeduplicateAfterResume() {
    var context = context(new OutputLimitRunBudget(2, 1000));
    var persisted =
        new java.util.concurrent.atomic.AtomicReference<RunExecutionContext.ProgressState>();
    context.setToolResultsCheckpoint(messages -> persisted.set(context.progressSnapshot()));
    read(context, 1);
    for (int i = 0; i < 4; i++) read(context, 1);
    context.preserveToolResults(java.util.List.of());
    assertThat(persisted.get().budget().stagnationReplans()).isZero();
    assertThat(context.requestReplan()).isNotNull();
    assertThat(persisted.get().budget().stagnationReplans()).isEqualTo(1);
    context.completeSubgoal("review");
    var resumed = context(new OutputLimitRunBudget(2, 1000));
    resumed.restoreProgress(context.progressSnapshot());
    resumed.completeSubgoal("review");
    assertThat(resumed.progressVersion()).isZero();
    assertThat(resumed.budgetSnapshot().stagnationReplans()).isEqualTo(1);
  }

  @Test
  void restoredSharedBudgetIsNotReplenishedByFreshReads() {
    var budget = new OutputLimitRunBudget(2, 1000);
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    var context = context(budget);
    for (int i = 1; i <= 96; i++) read(context, i);
    assertThatThrownBy(context::requestReplan).hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(context.diagnosis().goalStagnant()).isTrue();
  }
}
