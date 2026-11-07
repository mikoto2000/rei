package dev.mikoto2000.rei.context;

/** Looks up an accepted cancellation request by Run ID across thread boundaries. */
@FunctionalInterface
public interface RunCancellationStatus {
  boolean isCancellationRequested(String runId);
}
