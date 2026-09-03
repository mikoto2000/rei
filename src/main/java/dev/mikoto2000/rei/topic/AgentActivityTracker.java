package dev.mikoto2000.rei.topic;

import java.time.Instant;

public interface AgentActivityTracker {
  Instant applicationStartedAt();
  Instant lastUserActivityAt();
  Instant lastAgentActivityAt();
  boolean isAgentBusy();
  long activityVersion();
  void recordUserActivity(Instant at);
  void recordAgentStarted(Instant at);
  void recordAgentCompleted(Instant at);
}
