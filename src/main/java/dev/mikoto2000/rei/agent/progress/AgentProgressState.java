package dev.mikoto2000.rei.agent.progress;

import java.util.List;

public record AgentProgressState(
    String goal,
    List<String> completedSteps,
    List<String> unresolvedIssues,
    List<String> observations,
    List<ToolExecutionObservation> toolHistory) {

  public AgentProgressState {
    completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
    unresolvedIssues = unresolvedIssues == null ? List.of() : List.copyOf(unresolvedIssues);
    observations = observations == null ? List.of() : List.copyOf(observations);
    toolHistory = toolHistory == null ? List.of() : List.copyOf(toolHistory);
  }

  public static AgentProgressState empty(String goal) {
    return new AgentProgressState(goal, List.of(), List.of(), List.of(), List.of());
  }
}
