package dev.mikoto2000.rei.temporal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface AgentScheduler {
  ScheduledAgentTask scheduleAfter(Duration duration, String action, String conversationId);

  ScheduledAgentTask scheduleAt(Instant executeAt, String action, String conversationId);

  List<ScheduledAgentTask> list();
}
