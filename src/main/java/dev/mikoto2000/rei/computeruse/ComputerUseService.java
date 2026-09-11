package dev.mikoto2000.rei.computeruse;

import java.util.*;
import java.util.function.*;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.*;

public final class ComputerUseService {
  private static final java.util.concurrent.atomic.AtomicBoolean DESKTOP_BUSY = new java.util.concurrent.atomic.AtomicBoolean();
  private final ScreenCapture capture;
  private final ComputerVisionModel model;
  private final ComputerInput input;
  private final UiStabilizer stabilizer;
  private final SafetyPolicy safety;
  private final BooleanSupplier cancelled;
  private final Consumer<ComputerProgress> events;
  private final int maxSteps;
  private final int historyLimit;

  public ComputerUseService(ScreenCapture capture, ComputerVisionModel model, ComputerInput input,
      UiStabilizer stabilizer, SafetyPolicy safety, BooleanSupplier cancelled,
      Consumer<ComputerProgress> events, int maxSteps, int historyLimit) {
    if (maxSteps < 1 || maxSteps > 200 || historyLimit < 1 || historyLimit > 20)
      throw new IllegalArgumentException("Invalid loop limits");
    this.capture = capture; this.model = model; this.input = input; this.stabilizer = stabilizer;
    this.safety = safety; this.cancelled = cancelled; this.events = events;
    this.maxSteps = maxSteps; this.historyLimit = historyLimit;
  }

  public ComputerUseResult run(String goal) {
    if (!DESKTOP_BUSY.compareAndSet(false, true)) return new ComputerUseResult(BUSY, 0, "Another desktop workflow is active");
    try { return executeLoop(goal); } finally { DESKTOP_BUSY.set(false); }
  }

  private ComputerUseResult executeLoop(String goal) {
    if (goal == null || goal.isBlank() || goal.length() > 4000) throw new IllegalArgumentException("Invalid goal");
    var history = new ArrayList<String>();
    emit(new ComputerProgress(0, "started", null, null, null, null, null, null));
    for (int step = 1; step <= maxSteps; step++) {
      var failure = CAPTURE_ERROR;
      try {
        checkCancelled();
        CapturedScreen screen = capture.captureScreen();
        checkCancelled();
        emit(new ComputerProgress(step, "observed", null, null, null, null, null, null));
        checkCancelled();
        failure = MODEL_ERROR;
        ComputerAction action = model.decide(new ComputerObservation(goal, screen, history, step, maxSteps));
        checkCancelled();
        ActionValidator.validate(action, screen);
        var decision = progress(step, "decided", action);
        emit(decision);
        checkCancelled();
        if (action instanceof ComputerAction.Done done) return finish(DONE, step, done.reason());
        if (action instanceof ComputerAction.Failed failed) return finish(FAILED, step, failed.reason());
        if (!safety.allows(action)) return finish(SAFETY_BLOCKED, step, "Policy requires approval or prohibits action");
        if (decision.confidence() != null && decision.confidence() < .8) action = new ComputerAction.Uncertain("Low confidence; observe again");
        checkCancelled();
        failure = ACTION_ERROR;
        emit(progress(step, "action_started", action));
        checkCancelled();
        if (!(action instanceof ComputerAction.Wait) && !(action instanceof ComputerAction.Uncertain)) input.execute(action, screen);
        checkCancelled();
        emit(progress(step, "action_completed", action));
        checkCancelled();
        failure = STABILIZATION_ERROR;
        stabilizer.awaitAfter(action);
        checkCancelled();
        var summary = progress(step, "history", action);
        history.add(summary.action() + (summary.target() == null ? "" : " " + summary.target() + " (" + summary.x() + "," + summary.y() + ")")
            + (summary.reason() == null ? "" : " " + summary.reason()) + "; dispatch only, goal not verified");
        if (history.size() > historyLimit) history.removeFirst();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return finish(CANCELLED, step, "Cancelled");
      } catch (java.util.concurrent.CancellationException error) {
        return finish(CANCELLED, step, "Cancelled");
      } catch (Exception error) {
        return finish(isCancelled() ? CANCELLED : failure, step, error.getClass().getSimpleName());
      }
    }
    return finish(MAX_STEPS, maxSteps, "Observation limit reached");
  }

  private ComputerUseResult finish(ComputerUseResult.Status status, int step, String reason) {
    emit(new ComputerProgress(step, "finished", null, null, null, null, null, status.name()));
    return new ComputerUseResult(status, step, reason);
  }

  private void emit(ComputerProgress progress) {
    // Observability must never cause a dispatched operation to be retried.
    try { events.accept(progress); } catch (RuntimeException ignored) { }
  }

  static ComputerProgress progress(int step, String phase, ComputerAction action) {
    ComputerAction.Target target = null;
    Double confidence = null;
    String reason = null;
    if (action instanceof ComputerAction.Click a) { target = a.target(); confidence = a.confidence(); }
    if (action instanceof ComputerAction.DoubleClick a) { target = a.target(); confidence = a.confidence(); }
    if (action instanceof ComputerAction.Uncertain a) reason = a.reason();
    if (action instanceof ComputerAction.Done a) reason = a.reason();
    if (action instanceof ComputerAction.Failed a) reason = a.reason();
    return new ComputerProgress(step, phase, action.getClass().getSimpleName(), target == null ? null : target.description(),
        target == null ? null : target.x(), target == null ? null : target.y(), confidence, reason);
  }

  private boolean isCancelled() { return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted(); }
  private void checkCancelled() {
    if (isCancelled()) throw new java.util.concurrent.CancellationException();
  }
}
