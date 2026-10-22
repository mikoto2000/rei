package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillResourceProgressTest {
  @TempDir Path root;

  private String page(int start, String text) {
    return "[{\"path\":\"references/report-format.md\",\"startLine\":"
        + start
        + ",\"content\":[\""
        + text
        + "\"],\"version\":\"v1\",\"error\":null}]";
  }

  private RunExecutionContext context() {
    return new RunExecutionContext(
        "run",
        new dev.mikoto2000.rei.llm.OutputLimitRunBudget(2, 1000),
        new ProgressEvaluator(root),
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
        e -> {});
  }

  @Test
  void consecutiveNewSkillPagesAreInformationWithoutClaimingDocumentReviewCoverage() {
    var c = context();
    for (int i = 1; i <= 6; i++) {
      c.beginIteration();
      c.recordTool(
          "readSkillResource",
          "{}",
          page(i, "line" + i),
          c.evaluator().beforeTool("readSkillResource", "{}"));
      c.endIteration();
      assertThat(c.detector().stagnationCount()).isZero();
    }
    assertThat(c.diagnosis().lastProgress().category())
        .isEqualTo(ProgressEvidence.Category.INFORMATION_DISCOVERED);
    assertThat(c.evaluator().informationSnapshot().files()).isEmpty();
    for (int i = 0; i < 4; i++) {
      c.beginIteration();
      c.recordTool(
          "readSkillResource",
          "{}",
          page(6, "line6"),
          c.evaluator().beforeTool("readSkillResource", "{}"));
      c.endIteration();
    }
    assertThat(c.detector().isStagnant()).isTrue();
  }

  @Test
  void exhaustedLegacyCheckpointCanBeCorrectedOnceByUnaccountedActualSkillOutput()
      throws Exception {
    var c = context();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    try (var data =
        getClass().getResourceAsStream("/checkpoint/skill-read-stalled-progress.json")) {
      c.restoreProgress(json.readValue(data, RunExecutionContext.ProgressState.class));
    }
    assertThat(c.reconcileSkillProgress(page(30, "new instruction"))).isTrue();
    assertThat(c.detector().stagnationCount()).isZero();
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
    assertThat(c.diagnosis().goalIdleIterations()).isEqualTo(28);
    var resumed = context();
    resumed.restoreProgress(
        json.readValue(
            json.writeValueAsString(c.progressSnapshot()),
            RunExecutionContext.ProgressState.class));
    for (int i = 0; i < 4; i++) {
      resumed.beginIteration();
      resumed.endIteration();
    }
    assertThat(resumed.reconcileSkillProgress(page(30, "new instruction"))).isFalse();
    assertThatThrownBy(resumed::requestReplan).isInstanceOf(ExecutionStoppedException.class);
  }

  @Test
  void failedEmptyAndMalformedSkillResultsCannotCorrectAStall() {
    var c = context();
    for (int i = 0; i < 4; i++) {
      c.beginIteration();
      c.endIteration();
    }
    for (String invalid :
        java.util.List.of(
            "[]", "null", "invalid", "[{\"path\":\"A\",\"error\":\"missing\",\"content\":[]} ]"))
      assertThat(c.reconcileSkillProgress(invalid)).isFalse();
    assertThat(c.detector().isStagnant()).isTrue();
  }

  @Test
  void spellingAndVersionMetadataCannotMintProgressAndFullLedgerCannotRepair() {
    var c = context();
    assertThat(c.reconcileSkillProgress(page(1, "same instruction"))).isTrue();
    assertThat(
            c.reconcileSkillProgress(
                page(1, "same instruction")
                    .replace(
                        "references/report-format.md", "references/../references/report-format.md")
                    .replace("v1", "v2")))
        .isFalse();
    var ledger = new InformationLedger();
    for (int i = 0; i < 1024; i++) ledger.observe(String.format("%064x", i));
    var full = context();
    full.evaluator().restoreInformation(ledger.snapshot());
    for (int i = 0; i < 4; i++) {
      full.beginIteration();
      full.endIteration();
    }
    assertThat(full.reconcileSkillProgress(page(100, "untracked instruction"))).isFalse();
    assertThat(full.detector().isStagnant()).isTrue();
  }
}
