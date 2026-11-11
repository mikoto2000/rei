package dev.mikoto2000.rei.core.execution;

/**
 * Optional output port for completed execution text; scheduling and ownership stay with the caller.
 */
@FunctionalInterface
public interface CompletedRunNarrator {
  /** Narrate completed output without changing synchronous command notification state. */
  void narrateCompletedRun(String text);
}
