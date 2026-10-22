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

class RepeatedCheckpointResumeTest {
  @TempDir Path root;

  private int runSequence;

  private RunExecutionContext context() {
    return context(new OutputLimitRunBudget(2, 1000));
  }

  private RunExecutionContext context(OutputLimitRunBudget budget) {
    return new RunExecutionContext(
        "run-" + ++runSequence,
        budget,
        new ProgressEvaluator(root),
        new AgentEventFactory(Clock.systemUTC()),
        event -> {});
  }

  private void tool(RunExecutionContext c, String name, String args, String result) {
    c.recordTool(name, args, result, c.evaluator().beforeTool(name, args));
  }

  private void metadata(RunExecutionContext c) {
    c.beginIteration();
    tool(
        c,
        "checkpointShow",
        "{\"taskId\":\"task\"}",
        "{\"schemaVersion\":1,\"taskId\":\"task\",\"revision\":1033,\"plan\":[]}");
    tool(
        c,
        "checkpointInspect",
        "{\"taskId\":\"task\"}",
        "{\"decision\":\"CONTINUE\",\"usable\":[],\"blockers\":[]}");
    c.endIteration();
  }

  private RunExecutionContext.ProgressState persisted(RunExecutionContext c) throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    return json.readValue(
        json.writeValueAsString(c.progressSnapshot()), RunExecutionContext.ProgressState.class);
  }

  @Test
  void anotherResumeCanUseRemainingRestorationBudgetWithoutRefillingIt() throws Exception {
    var budget = new OutputLimitRunBudget(2, 1000);
    budget.tryConsumeStagnationReplan();
    budget.tryConsumeStagnationReplan();
    var initial = context(budget);
    initial.beginContextRestoration();
    metadata(initial);
    for (int i = 0; i < 4; i++) {
      initial.beginIteration();
      initial.endIteration();
    }
    var saved = persisted(initial);
    assertThat(saved.continuity().evaluator().restorationReads()).isEqualTo(2);
    for (int used = 4; used <= 8; used += 2) {
      var resumed = context();
      resumed.restoreProgress(saved);
      resumed.beginContextRestoration();
      metadata(resumed);
      assertThat(resumed.requestReplan()).isNull();
      assertThat(resumed.detector().stagnationCount()).isEqualTo(4);
      assertThat(resumed.budgetSnapshot().stagnationReplans()).isEqualTo(2);
      saved = persisted(resumed);
      assertThat(saved.continuity().evaluator().restorationReads()).isEqualTo(used);
    }
    var exhausted = context();
    exhausted.restoreProgress(saved);
    exhausted.beginContextRestoration();
    metadata(exhausted);
    assertThatThrownBy(exhausted::requestReplan).isInstanceOf(ExecutionStoppedException.class);
    assertThat(exhausted.progressSnapshot().continuity().evaluator().restorationReads())
        .isEqualTo(8);
  }

  @Test
  void preparatoryInformationDoesNotCancelRemainingIdentifiedRestoration() throws Exception {
    var original = context();
    String read = "{\"path\":\"A\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"line\"]}";
    original.beginIteration();
    tool(original, "readMultiFile", "{}", read);
    original.endIteration();
    var resumed = context();
    resumed.restoreProgress(persisted(original));
    resumed.beginContextRestoration();
    metadata(resumed);
    resumed.beginIteration();
    tool(
        resumed,
        "countFileLines",
        "{}",
        "{\"files\":[{\"path\":\"A\",\"lineCount\":1,\"error\":null}]}");
    resumed.endIteration();
    resumed.beginIteration();
    tool(resumed, "readMultiFile", "{}", read);
    resumed.endIteration();
    assertThat(resumed.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(3);
    assertThat(resumed.detector().stagnationCount()).isZero();
    resumed.beginIteration();
    var before = resumed.evaluator().beforeTool("writeTextFile", "{\"path\":\"findings.md\"}");
    Files.writeString(root.resolve("findings.md"), "finding verified");
    resumed.recordTool("writeTextFile", "{}", "{\"success\":true}", before);
    resumed.endIteration();
    assertThat(resumed.evaluator().restoringContext()).isFalse();
  }

  @Test
  void previousReleaseCheckpointWithUsedMetadataHistoryCanResumeAndProduceWork() throws Exception {
    // Reduced from the reported revision 1042: retain the control state, omit user content/paths
    // and re-key metadata for the test task.
    String checkpoint =
        """
        {"information":{"files":[],"identities":[],"historicalUnknown":false},
         "budget":{"replans":0,"llmCalls":247,"totalTokens":0,"usageUnknown":false,
                   "stagnationReplans":2,"argumentCorrections":0},
         "acquisitionOnlyIterations":6,"unknownIterations":0,"unknownReplanNotices":0,
         "continuity":{"detector":{"stagnationCount":5,"replanCount":0,"replanRequested":true,
                                      "lastToolFingerprint":"checkpointInspect","lastFailureFingerprint":"runCommand"},
          "evaluator":{"restoredRanges":{"files":[],"identities":[
             "2553c4da11011c2c5fdc434aef2ec0eb71748633f933039da25fe2a27f7aec4e",
             "110aaba489e9aa09ae8cf00d34f0f84a1374ccbb10d0964cb6850388fbacf5ed"],"historicalUnknown":false},
                       "restorationReads":2,"revisions":[],"completedSteps":[],"startedSteps":[]},
          "goalIdleIterations":15,"coverageIterations":4,"lastProgress":null,"recoveries":[],
          "reason":"CONTEXT_RESTORATION_FAILED"}}
        """;
    var resumed = context();
    resumed.restoreProgress(
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(checkpoint, RunExecutionContext.ProgressState.class));
    resumed.beginContextRestoration();
    metadata(resumed);
    assertThat(resumed.requestReplan()).isNull();
    assertThat(resumed.detector().stagnationCount()).isEqualTo(5);
    assertThat(resumed.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(4);
    resumed.beginIteration();
    var before = resumed.evaluator().beforeTool("writeTextFile", "{\"path\":\"findings.md\"}");
    Files.writeString(root.resolve("findings.md"), "verified finding");
    resumed.recordTool("writeTextFile", "{}", "{\"success\":true}", before);
    resumed.endIteration();
    assertThat(resumed.requestReplan()).isNull();
    assertThat(resumed.detector().stagnationCount()).isZero();
    assertThat(resumed.diagnosis().goalIdleIterations()).isZero();
    assertThat(resumed.budgetSnapshot().stagnationReplans()).isEqualTo(2);
  }
}
