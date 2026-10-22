package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumeObservationTest {
  @TempDir Path root;

  @Test
  void projectPlanRemainsBoundWithoutProjectScope() {
    var project = new java.util.concurrent.atomic.AtomicReference<>("review");
    try (var beans =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      beans
          .getBeanFactory()
          .registerScope(
              "reiProject", new dev.mikoto2000.rei.core.project.ProjectBeanScope(project::get));
      beans.register(dev.mikoto2000.rei.core.actionplan.ActionPlanConfiguration.class);
      beans.refresh();
      var plan = beans.getBean(dev.mikoto2000.rei.core.actionplan.ActionPlan.class);
      plan.addStep("review");
      plan.startStep("step-1");
      var evaluator = new ProgressEvaluator(root, plan);
      var before = evaluator.beforeTool("checkpointPlan", "{}");
      plan.completeStep("step-1");
      project.set("global");
      assertThat(evaluator.afterTool("checkpointPlan", "{}", "{}", before))
          .extracting(ProgressEvidence::kind)
          .containsExactly(ProgressEvent.SUBGOAL_COMPLETED);
      project.set("review");
      plan.addStep("fix");
      plan.startStep("step-2");
      project.set("global");
      assertThat(evaluator.currentStep()).isEqualTo("step-2");
      assertThat(evaluator.remainingSteps()).containsExactly("step-2");
      assertThat(plan.steps()).isEmpty();
    }
  }

  @Test
  void lineCountsAreInformationOnceButNeverReadCoverage() {
    var evaluator = new ProgressEvaluator(root);
    String counts =
        """
        {"files":[{"path":"docs/a.md","lineCount":15,"error":null}]}
        """;
    var before = evaluator.beforeTool("countFileLines", "{}");
    assertThat(evaluator.afterTool("countFileLines", "{}", counts, before))
        .extracting(ProgressEvidence::category)
        .containsExactly(ProgressEvidence.Category.INFORMATION_DISCOVERED);
    assertThat(evaluator.afterTool("countFileLines", "{}", counts, before)).isEmpty();
    assertThat(evaluator.informationSnapshot().files()).isEmpty();
    assertThat(
            evaluator.afterTool(
                "countFileLines",
                "{}",
                "{\"files\":[{\"path\":\"missing\",\"lineCount\":0,\"error\":\"missing\"}]}",
                before))
        .isEmpty();
  }

  @Test
  void processObservationsDeduplicateStableFactsAndSurviveResume() {
    var evaluator = new ProgressEvaluator(root);
    String result =
        """
        {"processId":"proc-old","status":"FAILED","found":false,"elapsedSeconds":10}
        """;
    var before = evaluator.beforeTool("getShellProcessStatus", "{}");
    assertThat(evaluator.afterTool("getShellProcessStatus", "{}", result, before))
        .extracting(ProgressEvidence::kind)
        .containsExactly(ProgressEvent.NEW_INFORMATION);
    assertThat(
            evaluator.afterTool(
                "getShellProcessStatus", "{}", result.replace(":10", ":20"), before))
        .isEmpty();
    var resumed = new ProgressEvaluator(root);
    resumed.restoreInformation(evaluator.informationSnapshot());
    assertThat(resumed.afterTool("getShellProcessStatus", "{}", result, before)).isEmpty();
  }

  @Test
  void resumedReconciliationCanAdvanceWithSpentBudgetButDuplicateLoopStillStops() {
    var budget = new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 1000);
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    var context =
        new RunExecutionContext(
            "run",
            budget,
            new ProgressEvaluator(root),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    for (int i = 0; i < 3; i++) {
      context.beginIteration();
      context.recordTool(
          "checkpointShow", "{}", "{}", context.evaluator().beforeTool("checkpointShow", "{}"));
      context.endIteration();
    }
    String result = "{\"processId\":\"proc-old\",\"status\":\"FAILED\",\"found\":false}";
    context.beginIteration();
    context.recordTool(
        "getShellProcessStatus",
        "{}",
        result,
        context.evaluator().beforeTool("getShellProcessStatus", "{}"));
    context.endIteration();
    assertThat(context.requestReplan()).isNull();
    var saved = context.progressSnapshot();
    var resumed =
        new RunExecutionContext(
            "resume",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 1000),
            new ProgressEvaluator(root),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    resumed.restoreProgress(saved);
    for (int i = 0; i < 4; i++) {
      resumed.beginIteration();
      resumed.recordTool(
          "getShellProcessStatus",
          "{}",
          result,
          resumed.evaluator().beforeTool("getShellProcessStatus", "{}"));
      resumed.endIteration();
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(resumed::requestReplan)
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(resumed.budgetSnapshot().stagnationReplans()).isEqualTo(2);
  }

  @Test
  void checkpointInspectionAtResumeDefersPendingStopOnlyOnceAcrossLineage() {
    var budget = new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 1000);
    budget.tryConsumeStagnationReplan();
    budget.tryConsumeStagnationReplan();
    var context =
        new RunExecutionContext(
            "run",
            budget,
            new ProgressEvaluator(root),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    for (int i = 0; i < 4; i++) {
      context.beginIteration();
      context.endIteration();
    }
    var saved = context.progressSnapshot();
    context.restoreProgress(saved);
    context.beginContextRestoration();
    String args = "{\"taskId\":\"task\"}";
    String checkpoint = "{\"schemaVersion\":1,\"taskId\":\"task\",\"revision\":976,\"plan\":[]}";
    context.beginIteration();
    context.recordTool(
        "checkpointShow", args, checkpoint, context.evaluator().beforeTool("checkpointShow", args));
    context.endIteration();
    assertThat(context.requestReplan()).isNull();
    assertThat(context.detector().stagnationCount()).isEqualTo(4);
    assertThat(context.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    context.restoreProgress(context.progressSnapshot());
    context.beginContextRestoration();
    context.beginIteration();
    context.recordTool(
        "checkpointShow",
        args,
        checkpoint.replace("976", "999"),
        context.evaluator().beforeTool("checkpointShow", args));
    context.endIteration();
    org.assertj.core.api.Assertions.assertThatThrownBy(context::requestReplan)
        .isInstanceOf(ExecutionStoppedException.class);
  }
}
