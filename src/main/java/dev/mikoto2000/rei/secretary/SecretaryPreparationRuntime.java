package dev.mikoto2000.rei.secretary;

import java.util.function.Consumer;

/** Existing owned READ_ONLY planner runtime. Never substitutes a writable execution mode. */
public interface SecretaryPreparationRuntime {
  record Request(String runId, String projectId, String sessionId, String objective, String kind) {}

  record Outcome(String status, String text, String reason) {}

  record Snapshot(String state, boolean active) {}

  interface CallBudget {
    boolean tryReserve();

    int remaining();
  }

  boolean available();

  void validate(String project, String session);

  void start(Request request, CallBudget budget, Consumer<Outcome> completed);

  Snapshot inspect(String project, String session, String run);

  void cancel(String project, String session, String run);
}
