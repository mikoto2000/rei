package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressRestorationTest {
  @TempDir Path root;
  private final ArrayList<AgentEvent> events = new ArrayList<>();

  private RunExecutionContext context() {
    return new RunExecutionContext(
        "run",
        new OutputLimitRunBudget(2, 500),
        new ProgressEvaluator(root),
        new AgentEventFactory(Clock.systemUTC()),
        events::add);
  }

  private void read(RunExecutionContext context, int line) {
    context.beginIteration();
    context.recordTool(
        "readMultiFile",
        "{}",
        "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":" + line + ",\"content\":[\"line\"]}",
        context.evaluator().beforeTool("readMultiFile", "{}"));
    context.endIteration();
  }

  @Test
  void identifiedRestorationPreservesLocalCounterAndBudgetWithoutDiscoveringInformation()
      throws Exception {
    var original = context();
    for (int i = 1; i <= 8; i++) read(original, i);
    for (int i = 0; i < 3; i++) read(original, 1);
    var json = new ObjectMapper();
    var saved =
        json.readValue(
            json.writeValueAsString(original.progressSnapshot()),
            RunExecutionContext.ProgressState.class);
    var resumed = context();
    resumed.restoreProgress(saved);
    resumed.beginContextRestoration();
    for (int i = 1; i <= 8; i++) {
      read(resumed, i);
      assertThat(resumed.requestReplan()).isNull();
    }
    assertThat(resumed.progressVersion()).isZero();
    assertThat(resumed.detector().stagnationCount()).isEqualTo(3);
    assertThat(resumed.budgetSnapshot()).isEqualTo(original.budgetSnapshot());
    assertThat(events)
        .anyMatch(
            e ->
                e.payload() instanceof ExecutionProgressPayload p
                    && p.evidence() != null
                    && p.evidence().kind() == ProgressEvent.CONTEXT_RESTORED);
    read(resumed, 1);
    assertThat(resumed.detector().isStagnant()).isTrue();
    var again = context();
    again.restoreProgress(resumed.progressSnapshot());
    again.beginContextRestoration();
    read(again, 2);
    assertThat(again.detector().isStagnant()).isTrue();
  }

  @Test
  void restorationDefersAlreadyPendingReplanWithoutErasingIt() {
    var original = context();
    read(original, 1);
    for (int i = 0; i < 4; i++) read(original, 1);
    var resumed = context();
    resumed.restoreProgress(original.progressSnapshot());
    resumed.beginContextRestoration();
    read(resumed, 1);
    assertThat(resumed.requestReplan()).isNull();
    assertThat(resumed.detector().isReplanRequested()).isTrue();
    assertThat(resumed.budgetSnapshot().stagnationReplans()).isZero();
    read(resumed, 1);
    assertThat(resumed.requestReplan()).isNotNull();
  }

  @Test
  void repeatedRestorationOfSameRangeCannotMintGraceAndUnknownHistoryFallsBack() {
    var original = context();
    read(original, 1);
    var resumed = context();
    resumed.restoreProgress(original.progressSnapshot());
    resumed.beginContextRestoration();
    read(resumed, 1);
    for (int i = 0; i < 4; i++) read(resumed, 1);
    assertThat(resumed.detector().isStagnant()).isTrue();
    var legacy = context();
    legacy.restoreProgress(null);
    legacy.beginContextRestoration();
    read(legacy, 1);
    assertThat(legacy.progressVersion()).isZero();
    assertThat(legacy.progressSnapshot().unknownIterations()).isEqualTo(1);
  }

  @Test
  void stepTransitionsDoNotMakeExistingCoverageNewAndNoOpWritesDoNotAdvanceWork() throws Exception {
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    plan.addStep("review");
    var evaluator = new ProgressEvaluator(root, plan);
    var before = evaluator.beforeTool("readMultiFile", "{}");
    String result = "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"line\"]}";
    evaluator.afterTool("readMultiFile", "{}", result, before);
    plan.startStep("step-1");
    var started = evaluator.afterTool("readMultiFile", "{}", result, before);
    assertThat(started).extracting(e -> e.category().name()).containsExactly("STEP_ADVANCED");
    plan.startStep("step-1");
    assertThat(evaluator.afterTool("readMultiFile", "{}", result, before)).isEmpty();
    var write = evaluator.beforeTool("writeTextFile", "{\"path\":\"result.md\"}");
    java.nio.file.Files.writeString(root.resolve("result.md"), "findings");
    assertThat(evaluator.afterTool("writeTextFile", "{}", "{}", write))
        .extracting(e -> e.category().name())
        .containsExactly("WORK_PRODUCT_UPDATED");
    write = evaluator.beforeTool("writeTextFile", "{\"path\":\"result.md\"}");
    assertThat(evaluator.afterTool("writeTextFile", "{}", "{}", write)).isEmpty();
  }
}
