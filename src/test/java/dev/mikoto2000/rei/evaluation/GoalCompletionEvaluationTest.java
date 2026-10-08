package dev.mikoto2000.rei.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GoalCompletionEvaluationTest {
  GoalCompletionEvaluation evaluator = new GoalCompletionEvaluation();
  GoalCompletionEvaluation.Observation observation(boolean claim, boolean exited, int repairs, int successes) {
    return new GoalCompletionEvaluation.Observation(claim, exited, repairs, successes, 2, 3, 1, 0,
        GoalCompletionEvaluation.Measurement.known(100), GoalCompletionEvaluation.Measurement.known(4),
        GoalCompletionEvaluation.Measurement.unavailable("No model provider in offline fixture"));
  }
  @Test void completionClaimWithoutIndependentEvidenceIsFalseCompletion() {
    var result = evaluator.score("multi-requirement", observation(true, true, 0, 0),
        List.of(new GoalCompletionEvaluation.Condition("file", true, false, "digest_mismatch")));
    assertFalse(result.verifiedCompletion()); assertTrue(result.falseCompletion());
    assertTrue(result.endedWithUnmet()); assertEquals(List.of("file"), result.unmet());
  }
  @Test void optionalFailureDoesNotBlockVerifiedCompletionAndRepairUsesAttemptDenominator() {
    var result = evaluator.score("repair", observation(true, true, 2, 1), List.of(
        new GoalCompletionEvaluation.Condition("required", true, true, "verified"),
        new GoalCompletionEvaluation.Condition("optional", false, false, "missing")));
    var report = evaluator.aggregate(List.of(result));
    assertTrue(result.verifiedCompletion()); assertEquals(1.0, report.verifiedCompletionRate());
    assertEquals(0.5, report.repairSuccessRate().value()); assertEquals(2, report.interventions());
    assertNull(report.tokens().value()); assertFalse(report.tokens().reason().isBlank());
  }
  @Test void waitingDoesNotCountAsEndedWithUnmetAndNoRepairRateIsUnavailable() {
    var result = evaluator.score("waiting", observation(false, false, 0, 0),
        List.of(new GoalCompletionEvaluation.Condition("job", true, false, "running")));
    assertFalse(result.endedWithUnmet()); assertFalse(result.verifiedCompletion());
    assertNull(evaluator.aggregate(List.of(result)).repairSuccessRate().value());
  }
  @Test void satisfiedChecksDoNotCompleteAnUnfinishedRun() {
    var result = evaluator.score("delivery", observation(false, false, 0, 0),
        List.of(new GoalCompletionEvaluation.Condition("saved", true, true, "verified")));
    assertFalse(result.verifiedCompletion());
  }
  @Test void invalidOrEmptyEvidenceAndDuplicateCasesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> evaluator.score("empty", observation(true,true,0,0),List.of()));
    assertThrows(IllegalArgumentException.class, () -> observation(true,true,0,1));
    var result = evaluator.score("same", observation(false,true,0,0),
        List.of(new GoalCompletionEvaluation.Condition("file",true,false,"missing")));
    assertThrows(IllegalArgumentException.class, () -> evaluator.aggregate(List.of(result,result)));
    assertThrows(IllegalArgumentException.class, () -> GoalCompletionEvaluation.Measurement.known(-1));
  }
  @Test void unavailableMeasurementsAreNotSilentlySummedAsZero() {
    var result = evaluator.score("unavailable", new GoalCompletionEvaluation.Observation(false,true,0,0,0,0,0,0,
        GoalCompletionEvaluation.Measurement.unavailable("No timer"),GoalCompletionEvaluation.Measurement.known(0),
        GoalCompletionEvaluation.Measurement.unavailable("No model")),
        List.of(new GoalCompletionEvaluation.Condition("file",true,false,"missing")));
    assertNull(evaluator.aggregate(List.of(result)).elapsedMillis().value());
  }
}
