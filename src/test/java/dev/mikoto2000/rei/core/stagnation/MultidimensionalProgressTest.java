package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultidimensionalProgressTest {
  @TempDir Path root;
  private final ObjectMapper json = new ObjectMapper();

  private java.util.List<ProgressEvidence> read(
      ProgressEvaluator evaluator, String path, String version, int start, int count) {
    var result =
        json.createObjectNode().put("path", path).put("version", version).put("startLine", start);
    result.set("content", json.valueToTree(Collections.nCopies(count, "line")));
    return evaluator.afterTool(
        "readMultiFile", "{}", result.toString(), evaluator.beforeTool("readMultiFile", "{}"));
  }

  @Test
  void thousandLinesAreFetchedAsCoverageWithoutClaimingReviewedWork() {
    var evaluator = new ProgressEvaluator(root);
    for (int start = 1; start <= 1000; start += 100) {
      var evidence = read(evaluator, "A", "v1", start, 100);
      assertThat(evidence).hasSize(1);
      assertThat(evidence.getFirst().category().name()).isEqualTo("COVERAGE_ADVANCED");
    }
    assertThat(read(evaluator, "A", "v1", 1, 100)).isEmpty();
    assertThat(read(evaluator, "A", "v1", 951, 100).getFirst().description())
        .contains("newLines=50");
    assertThat(read(evaluator, "B", "v1", 1, 100)).hasSize(1);
    assertThat(read(evaluator, "A", "v2", 1, 100)).hasSize(1);
  }

  @Test
  void onlyIndependentlyVerifiedStageEvidenceCountsAsWorkAndOnlyOnce() {
    var evaluator = new ProgressEvaluator(root);
    String verified =
        "{\"stageId\":\"review\",\"planVersion\":1,\"observation\":{\"result\":\"VERIFIED\",\"artifactVersions\":{\"result.md\":\"sha\"}}}";
    var before = evaluator.beforeTool("attachGoalStageEvidence", "{}");
    assertThat(
            evaluator.afterTool(
                "attachGoalStageEvidence",
                "{}",
                verified.replace("VERIFIED", "UNSATISFIED"),
                before))
        .isEmpty();
    assertThat(evaluator.afterTool("attachGoalStageEvidence", "{}", verified, before))
        .extracting(e -> e.category().name())
        .containsExactly("WORK_PRODUCT_UPDATED");
    assertThat(evaluator.afterTool("attachGoalStageEvidence", "{}", verified, before)).isEmpty();
  }

  @Test
  void fullWorkHistoryCanResumeAlongsideCurrentPlanWithoutGrowingBeyondCapacity() {
    var original = new ProgressEvaluator(root);
    for (int i = 0; i < 1024; i++) assertThat(original.completeSubgoal("goal-" + i)).isTrue();
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    plan.addStep("current");
    plan.completeStep("step-1");
    var resumed = new ProgressEvaluator(root, plan);
    resumed.restore(original.snapshot());
    assertThat(resumed.snapshot().completedSteps()).hasSize(1024);
    assertThat(resumed.completeSubgoal("goal-0")).isFalse();
    assertThat(resumed.completeSubgoal("new")).isFalse();
  }

  @Test
  void saturatedWorkHistoryReportsUnknownInsteadOfClaimingAnUnchangedArtifact() throws Exception {
    var evaluator = new ProgressEvaluator(root);
    var revisions =
        java.util.stream.IntStream.range(0, 1024)
            .mapToObj(i -> String.format("%064x", i))
            .collect(java.util.stream.Collectors.toSet());
    evaluator.restore(
        new ProgressEvaluator.State(
            new InformationLedger().snapshot(),
            0,
            revisions,
            java.util.Set.of(),
            java.util.Set.of()));
    var before = evaluator.beforeTool("writeTextFile", "{\"path\":\"result.md\"}");
    java.nio.file.Files.writeString(root.resolve("result.md"), "new findings");
    assertThat(evaluator.afterTool("writeTextFile", "{}", "{}", before))
        .extracting(ProgressEvidence::kind)
        .containsExactly(ProgressEvent.INFORMATION_UNKNOWN);
    assertThat(evaluator.snapshot().revisions()).hasSize(1024);
  }

  @Test
  void identityCapacityDoesNotPreventRangeTrackingAndRetainedRangesStayExact() {
    var evaluator = new ProgressEvaluator(root);
    for (int i = 0; i < 1500; i++)
      evaluator.afterTool(
          "findFile", "{}", "[\"file" + i + "\"]", evaluator.beforeTool("findFile", "{}"));
    assertThat(evaluator.informationSnapshot().identities()).hasSize(InformationLedger.LIMIT);
    assertThat(read(evaluator, "A", "v1", 1, 100)).hasSize(1);
    assertThat(read(evaluator, "A", "v1", 50, 100).getFirst().description())
        .contains("newLines=49");
    assertThat(read(evaluator, "A", "v1", 1, 149)).isEmpty();
    assertThat(evaluator.informationSnapshot().files().getFirst().ranges())
        .containsExactly(new InformationLedger.LineRange(1, 150));
  }
}
