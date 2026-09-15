package dev.mikoto2000.rei.application.run;

import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Monitor-protected transitions publish an immutable snapshot of status and metadata together. */
public final class RunRegistry {
  public static final Duration RETENTION = Duration.ofMinutes(30);
  private final Clock clock;
  private final Map<String, RunSnapshot> runs = new LinkedHashMap<>();
  public RunRegistry(Clock clock) { this.clock = clock; }

  public synchronized void register(AgentRunContext context) {
    if (runs.putIfAbsent(context.runId(), new RunSnapshot(context, RunStatus.QUEUED, null, null, null)) != null)
      throw new IllegalArgumentException("Duplicate run");
  }
  public synchronized RunSnapshot get(String runId) {
    var snapshot = runs.get(runId);
    if (snapshot == null) throw new RunNotFoundException();
    return snapshot;
  }
  public synchronized boolean transition(String runId, RunStatus next, RunFailure failure) {
    var current = get(runId);
    if (current.status().isTerminal() || next == RunStatus.QUEUED || next == current.status()) return false;
    if (current.status() == RunStatus.QUEUED && next != RunStatus.RUNNING && next != RunStatus.CANCELLED) return false;
    runs.put(runId, new RunSnapshot(current.context(), next,
        next == RunStatus.RUNNING ? clock.instant() : current.startedAt(),
        next.isTerminal() ? clock.instant() : null, next == RunStatus.FAILED ? failure : null));
    return true;
  }
  public synchronized List<String> purgeExpired() {
    var expired = runs.entrySet().stream().filter(entry -> entry.getValue().completedAt() != null
        && !clock.instant().isBefore(entry.getValue().completedAt().plus(RETENTION))).map(Map.Entry::getKey).toList();
    expired.forEach(runs::remove);
    return expired;
  }
}
