package dev.mikoto2000.rei.agent.progress;

public class AgentNoProgressException extends RuntimeException {

  private final ProgressTrackerSnapshot snapshot;

  public AgentNoProgressException(ProgressTrackerSnapshot snapshot) {
    super("Agent stopped because no meaningful progress was detected for "
        + snapshot.maxNoProgressIterations() + " consecutive iterations.");
    this.snapshot = snapshot;
  }

  public ProgressTrackerSnapshot snapshot() {
    return snapshot;
  }
}
