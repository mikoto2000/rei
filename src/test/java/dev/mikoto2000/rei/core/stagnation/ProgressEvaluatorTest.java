package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressEvaluatorTest {
  @TempDir Path root;

  @Test
  void readFailureThenActualContentIsErrorResolved() {
    var evaluator = new ProgressEvaluator(root);
    String args = "{\"path\":\"A\"}";
    evaluator.recordFailure("readFile", args);
    assertThat(evaluator.afterTool("readFile", args, "file contents", evaluator.beforeTool("readFile", args)))
        .extracting(ProgressEvidence::kind).contains(ProgressEvent.ERROR_RESOLVED);
  }

  @Test
  void structuredReadFailureDoesNotCountAndRangesDeduplicateByLine() {
    var evaluator = new ProgressEvaluator(root);
    var before = evaluator.beforeTool("readMultiFile", "{}");
    assertThat(evaluator.afterTool("readMultiFile", "{}",
        "[{\"path\":\"A\",\"error\":\"missing\",\"content\":[]}]", before)).isEmpty();
    String first = "[{\"path\":\"A\",\"startLine\":1,\"content\":[\"one\",\"two\"]}]";
    assertThat(evaluator.afterTool("readMultiFile", "{}", first, before))
        .extracting(ProgressEvidence::kind).containsExactly(ProgressEvent.ERROR_RESOLVED, ProgressEvent.NEW_INFORMATION);
    assertThat(evaluator.afterTool("readMultiFile", "{}",
        "[{\"path\":\"A\",\"startLine\":2,\"content\":[\"two\"]}]", before)).isEmpty();
    assertThat(evaluator.afterTool("readMultiFile", "{}",
        "[{\"path\":\"A\",\"startLine\":3,\"content\":[\"three\"]}]", before)).hasSize(1);
  }

  @Test
  void planCompletionCountsOnceAndNotMerelyChangingStep() {
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    plan.addStep("verify");
    var evaluator = new ProgressEvaluator(root, plan);
    var before = evaluator.beforeTool("plan", "{}");
    plan.startStep("step-1");
    assertThat(evaluator.afterTool("plan", "{}", "success", before)).isEmpty();
    plan.completeStep("step-1");
    assertThat(evaluator.afterTool("plan", "{}", "success", before))
        .extracting(ProgressEvidence::kind).containsExactly(ProgressEvent.SUBGOAL_COMPLETED);
    assertThat(evaluator.afterTool("plan", "{}", "success", before)).isEmpty();
  }

  @Test
  void equivalentSearchResultsAreNotNewInformationForDifferentQuery() {
    var evaluator = new ProgressEvaluator(root);
    var before = evaluator.beforeTool("findFile", "{}");
    assertThat(evaluator.afterTool("findFile", "{\"pattern\":\"A*\"}", "[\"A.java\"]", before)).hasSize(1);
    assertThat(evaluator.afterTool("findFile", "{\"pattern\":\"*.java\"}", "[\"A.java\"]", before)).isEmpty();
  }

  @Test
  void onlyNewReadContentCountsEvenWithReorderedArguments() {
    var evaluator = new ProgressEvaluator(root);
    var before = evaluator.beforeTool("readMultiFile", "{}");
    String result = "[{\"path\":\"A\",\"startLine\":1,\"content\":[\"one\"],\"error\":null}]";
    assertThat(evaluator.afterTool("readMultiFile", "{}", result, before))
        .extracting(ProgressEvidence::kind).containsExactly(ProgressEvent.NEW_INFORMATION);
    assertThat(evaluator.afterTool("readMultiFile", "{\"unused\":1}", result, before)).isEmpty();
    assertThat(evaluator.afterTool("readMultiFile", "{}", result.replace("one", "two"), before)).hasSize(1);
    assertThat(evaluator.afterTool("readMultiFile", "{}", result.replace("\"A\"", "\"B\""), before)).hasSize(1);
  }

  @Test
  void writeRequiresActualContentChange() throws Exception {
    Files.writeString(root.resolve("A"), "before");
    var evaluator = new ProgressEvaluator(root);
    String args = "{\"files\":[{\"path\":\"A\",\"content\":\"after\"}]}";
    var before = evaluator.beforeTool("writeMultiFile", args);
    assertThat(evaluator.afterTool("writeMultiFile", args, "[]", before)).isEmpty();
    Files.writeString(root.resolve("A"), "after");
    assertThat(evaluator.afterTool("writeMultiFile", args, "[]", before))
        .extracting(ProgressEvidence::kind).containsExactly(ProgressEvent.STATE_CHANGED);
  }

  @Test
  void structuredFailureIsNotInformationAndSuccessResolvesSameAction() {
    var evaluator = new ProgressEvaluator(root);
    String args = "{\"command\":\"mvn test\",\"timeout\":100}";
    var before = evaluator.beforeTool("runCommand", args);
    assertThat(evaluator.afterTool("runCommand", args,
        "{\"exitCode\":1,\"stdout\":\"failed\",\"status\":\"failed\"}", before)).isEmpty();
    assertThat(evaluator.afterTool("runCommand", "{\"timeout\":100,\"command\":\"mvn test\"}",
        "{\"exitCode\":0,\"stdout\":\"passed\",\"status\":\"completed\"}", before))
        .extracting(ProgressEvidence::kind).contains(ProgressEvent.ERROR_RESOLVED);
  }

  @Test
  void arbitrarySuccessAndChangingClockAreNotProgress() {
    var evaluator = new ProgressEvaluator(root);
    assertThat(evaluator.afterTool("unknown", "{}", "success", evaluator.beforeTool("unknown", "{}"))).isEmpty();
    assertThat(evaluator.afterTool("now", "{}", "2026-09-09T10:00", evaluator.beforeTool("now", "{}"))).isEmpty();
  }
}
