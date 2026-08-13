package dev.mikoto2000.rei.agent.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AgentProgressTrackerTest {

  @Test
  void completedStepIncreaseIsMeaningfulProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 3);

    ProgressEvaluation evaluation = tracker.evaluate(
        state(List.of("read files"), List.of("fix bug"), List.of(), List.of()),
        state(List.of("read files", "identified cause"), List.of("fix bug"), List.of(), List.of()));

    assertTrue(evaluation.progressed());
    assertEquals(ProgressLevel.MEANINGFUL, evaluation.level());
  }

  @Test
  void unresolvedIssueDecreaseIsMeaningfulProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 3);

    ProgressEvaluation evaluation = tracker.evaluate(
        state(List.of(), List.of("find file", "fix bug"), List.of(), List.of()),
        state(List.of(), List.of("fix bug"), List.of(), List.of()));

    assertTrue(evaluation.progressed());
  }

  @Test
  void newObservationIsMeaningfulProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 3);

    ProgressEvaluation evaluation = tracker.evaluate(
        state(List.of(), List.of("fix bug"), List.of("A.java contains handler"), List.of()),
        state(List.of(), List.of("fix bug"), List.of("A.java contains handler", "B.java throws error"), List.of()));

    assertTrue(evaluation.progressed());
  }

  @Test
  void identicalStateIsNoProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 3);
    AgentProgressState state = state(List.of("read files"), List.of("fix bug"), List.of("same"), List.of());

    ProgressEvaluation evaluation = tracker.evaluate(state, state);

    assertFalse(evaluation.progressed());
    assertEquals(ProgressLevel.NONE, evaluation.level());
  }

  @Test
  void whitespaceAndVolatileDifferencesDoNotCountAsProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 3);

    ProgressEvaluation evaluation = tracker.evaluate(
        state(List.of(), List.of("fix bug"), List.of("error id=550e8400-e29b-41d4-a716-446655440000 at 2026-08-14T07:00:00Z"), List.of()),
        state(List.of(), List.of("fix bug"), List.of(" error   id=550e8400-e29b-41d4-a716-446655440111 at 2026-08-14T07:05:00Z "), List.of()));

    assertFalse(evaluation.progressed());
  }

  @Test
  void repeatedToolErrorIsNoProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 3);

    ProgressEvaluation evaluation = tracker.evaluate(
        state(List.of(), List.of("fetch url"), List.of(), List.of(tool("curl", "url=a", "Connection refused", true))),
        state(List.of(), List.of("fetch url"), List.of(), List.of(tool("wget", "url=a", "Connection refused", true))));

    assertFalse(evaluation.progressed());
  }

  @Test
  void differentToolCallsWithSameResultAreNoProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 3);

    ProgressEvaluation evaluation = tracker.evaluate(
        state(List.of(), List.of("find file"), List.of(), List.of(tool("rg", "foo", "src/A.java", false))),
        state(List.of(), List.of("find file"), List.of(), List.of(tool("grep", "foo", "src/A.java", false))));

    assertFalse(evaluation.progressed());
  }

  @Test
  void noProgressCounterStopsAtConfiguredThresholdAndResetsOnProgress() {
    AgentProgressTracker tracker = new AgentProgressTracker("goal", 2);
    AgentProgressState unchanged = state(List.of(), List.of("fix bug"), List.of("same"), List.of());
    tracker.update(unchanged);

    ProgressTrackerSnapshot first = tracker.update(unchanged);
    ProgressTrackerSnapshot second = tracker.update(unchanged);

    assertEquals(1, first.noProgressCount());
    assertEquals(2, second.noProgressCount());
    assertTrue(second.shouldStop());

    ProgressTrackerSnapshot progressed = tracker.update(
        state(List.of("fixed bug"), List.of(), List.of("same"), List.of()));

    assertEquals(0, progressed.noProgressCount());
    assertFalse(progressed.shouldStop());
  }

  private AgentProgressState state(
      List<String> completedSteps,
      List<String> unresolvedIssues,
      List<String> observations,
      List<ToolExecutionObservation> tools) {
    return new AgentProgressState("goal", completedSteps, unresolvedIssues, observations, tools);
  }

  private ToolExecutionObservation tool(String name, String arguments, String result, boolean error) {
    return new ToolExecutionObservation(name, arguments, result, error);
  }
}
