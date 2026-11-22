package dev.mikoto2000.rei.runmanagement;

import java.time.Instant;

/** A retained run, ordered by completion time, or start time while still active. */
public record RunHistoryEntry(
    String projectId,
    String projectName,
    String runId,
    String request,
    String status,
    Instant startedAt,
    Instant completedAt,
    String failure) {
  public Instant orderTime() {
    return completedAt != null ? completedAt : startedAt != null ? startedAt : Instant.MIN;
  }
}
