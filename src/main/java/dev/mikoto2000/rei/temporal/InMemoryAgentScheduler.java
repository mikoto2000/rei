package dev.mikoto2000.rei.temporal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

@Component
public class InMemoryAgentScheduler implements AgentScheduler {
  private final Clock clock;
  private final List<ScheduledAgentTask> tasks = new CopyOnWriteArrayList<>();

  public InMemoryAgentScheduler(Clock clock) {
    this.clock = clock;
  }

  @Override
  public ScheduledAgentTask scheduleAfter(Duration duration, String action, String conversationId) {
    Instant createdAt = Instant.now(clock);
    return save(new ScheduledAgentTask(newId(), createdAt, createdAt.plus(duration), action, conversationId));
  }

  @Override
  public ScheduledAgentTask scheduleAt(Instant executeAt, String action, String conversationId) {
    return save(new ScheduledAgentTask(newId(), Instant.now(clock), executeAt, action, conversationId));
  }

  @Override
  public List<ScheduledAgentTask> list() {
    return List.copyOf(tasks);
  }

  private ScheduledAgentTask save(ScheduledAgentTask task) {
    tasks.add(task);
    return task;
  }

  private String newId() {
    return "timer-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
