package dev.mikoto2000.rei.secretary;

/** Explicitly approved existing Goals retain their planner, stages, verification and budgets. */
public interface SecretaryGoalRuntime {
  record Goal(
      String id,
      String sessionId,
      String fingerprint,
      String state,
      int remainingRuns,
      int remainingCalls,
      int callsUsed,
      int attempts,
      String runId,
      boolean verified,
      String reason) {}

  Goal describe(String project, String goal);

  void start(String project, String goal, String fingerprint);

  default void start(
      String project,
      String goal,
      String fingerprint,
      java.util.function.BooleanSupplier admission) {
    if (!admission.getAsBoolean()) throw new IllegalStateException("Secretary admission revoked");
    start(project, goal, fingerprint);
  }

  void stop(String project, String goal, boolean cancel);

  default String stopRun(String project, String goal, boolean cancel) {
    var before = describe(project, goal);
    stop(project, goal, cancel);
    return before.runId();
  }
}
