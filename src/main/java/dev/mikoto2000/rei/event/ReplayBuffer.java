package dev.mikoto2000.rei.event;

import java.time.*;
import java.util.*;

/** Accessed under the owning EventBus monitor. Sequence numbers are supplied by that bus. */
public final class ReplayBuffer {
  private static final class History {
    final ArrayDeque<AgentEvent> events = new ArrayDeque<>();
    long evictedThrough, latest;
    Long terminalSequence;
    Instant completedAt;
  }
  public record Snapshot(List<AgentEvent> events, long latestSequence, Long terminalSequence) {}
  private final Map<String, History> runs = new HashMap<>();
  private final int capacity;
  private final Clock clock;
  public ReplayBuffer(int capacity, Clock clock) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity; this.clock = clock;
  }
  public void append(AgentEvent event) {
    if (event.runId() == null) return;
    var history = runs.computeIfAbsent(event.runId(), ignored -> new History());
    history.events.addLast(event); history.latest = event.sequence();
    while (history.events.size() > capacity) history.evictedThrough = history.events.removeFirst().sequence();
    if (history.terminalSequence == null && (event.type() == AgentEventType.AGENT_RUN_COMPLETED
        || event.type() == AgentEventType.AGENT_RUN_FAILED || event.type() == AgentEventType.AGENT_RUN_CANCELLED)) {
      history.terminalSequence = event.sequence(); history.completedAt = clock.instant();
    }
  }
  public Snapshot snapshot(String runId, long fromSequence) {
    if (fromSequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
    var history = runs.get(runId);
    if (history == null) return new Snapshot(List.of(), 0, null);
    if (fromSequence < history.evictedThrough) throw new ReplayGapException();
    return new Snapshot(history.events.stream().filter(event -> event.sequence() > fromSequence).toList(),
        history.latest, history.terminalSequence);
  }
  public void purgeRun(String runId) { runs.remove(runId); }
  public void purgeExpired() {
    purgeExpired(Set.of());
  }
  public void purgeExpired(Set<String> protectedRuns) {
    runs.entrySet().removeIf(entry -> !protectedRuns.contains(entry.getKey()) && entry.getValue().completedAt != null
        && !clock.instant().isBefore(entry.getValue().completedAt.plus(Duration.ofMinutes(30))));
  }
}
