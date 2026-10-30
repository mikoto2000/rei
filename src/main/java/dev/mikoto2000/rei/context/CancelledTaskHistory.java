package dev.mikoto2000.rei.context;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable automatic-context policy; archived records remain available for explicit retrieval. */
public record CancelledTaskHistory(
    Set<String> runIds, String generation, Set<String> laterRunIds, List<Attempt> attempts) {
  public static final String KEY = "rei.cancelledTaskHistory";
  public static final String GENERATION = "rei.historyGeneration";

  /** Only the lifecycle facts needed for projection, independent of storage and chat classes. */
  public record Attempt(String runId, String status, Instant startedAt) {}

  public CancelledTaskHistory {
    runIds = Set.copyOf(runIds);
    laterRunIds = Set.copyOf(laterRunIds);
    attempts = List.copyOf(attempts);
  }

  public static CancelledTaskHistory from(List<Attempt> attempts) {
    var hidden = new HashSet<String>();
    var later = new HashSet<String>();
    String generation = "";
    for (int i = 0; i < attempts.size(); i++) {
      var attempt = attempts.get(i);
      if (!"CANCELLED".equals(attempt.status())) {
        later.add(attempt.runId());
        continue;
      }
      later.clear();
      generation = attempt.runId();
      hidden.add(attempt.runId());
      // Include a failed request incorrectly continued by a later greeting. Do not cross a
      // completed turn or an independent running turn.
      for (int j = i - 1; j >= 0; j--) {
        var previous = attempts.get(j);
        if (!"FAILED".equals(previous.status())) break;
        hidden.add(previous.runId());
      }
    }
    return new CancelledTaskHistory(hidden, generation, later, attempts);
  }

  public boolean allowsState(String ownerRunId) {
    return runIds.isEmpty() || ownerRunId != null && laterRunIds.contains(ownerRunId);
  }

  public static boolean allowsState(Map<String, Object> context, String ownerRunId) {
    return !(context.get(KEY) instanceof CancelledTaskHistory cancelled)
        || cancelled.allowsState(ownerRunId);
  }

  public boolean hides(String runId) {
    return runIds.contains(runId);
  }

  /** Builds one reusable time index for a batch of history entries; ties retain the first owner. */
  public java.util.function.Predicate<Instant> indexedTimestampFilter() {
    if (runIds.isEmpty()) return timestamp -> false;
    var timeline = new java.util.TreeMap<Instant, Attempt>();
    boolean unknownHidden = false;
    for (var attempt : attempts) {
      if (attempt.startedAt() != null) timeline.putIfAbsent(attempt.startedAt(), attempt);
      else if (hides(attempt.runId())) unknownHidden = true;
    }
    boolean fallback = unknownHidden;
    return timestamp -> {
      if (timestamp == null) return true;
      var owner = timeline.floorEntry(timestamp);
      return owner == null ? fallback : hides(owner.getValue().runId());
    };
  }

  public boolean hides(Instant timestamp) {
    if (runIds.isEmpty()) return false;
    if (timestamp == null) return true;
    Attempt owner = null;
    for (var attempt : attempts) {
      if (attempt.startedAt() != null
          && !attempt.startedAt().isAfter(timestamp)
          && (owner == null || attempt.startedAt().isAfter(owner.startedAt()))) owner = attempt;
    }
    return owner != null
        ? hides(owner.runId())
        : attempts.stream().anyMatch(a -> hides(a.runId()) && a.startedAt() == null);
  }
}
