package dev.mikoto2000.rei.context;

import java.util.List;

/**
 * Immutable assistant progress shared by work records and persistence, independent of execution.
 */
public record TaskStateSnapshot(
    String goal, String status, List<String> completed, List<String> pending) {
  public TaskStateSnapshot {
    completed = List.copyOf(completed);
    pending = List.copyOf(pending);
  }
}
