package dev.mikoto2000.rei.agent.progress;

import java.util.ArrayList;
import java.util.List;

public class AgentProgressSession {

  private final String goal;
  private final AgentProgressTracker tracker;
  private final List<ToolExecutionObservation> toolHistory = new ArrayList<>();

  AgentProgressSession(String goal, int maxNoProgressIterations) {
    this.goal = goal;
    this.tracker = new AgentProgressTracker(goal, maxNoProgressIterations);
  }

  public synchronized ProgressTrackerSnapshot recordToolResult(
      String toolName,
      String arguments,
      String result,
      boolean error) {
    toolHistory.add(new ToolExecutionObservation(toolName, arguments, result, error));
    return tracker.update(new AgentProgressState(goal, List.of(), List.of(), List.of(), List.copyOf(toolHistory)));
  }

  public synchronized ProgressTrackerSnapshot snapshot() {
    return tracker.snapshot();
  }
}
