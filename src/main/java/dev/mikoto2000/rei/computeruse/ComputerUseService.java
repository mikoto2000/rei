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
  private final ComputerDiagnostics diagnostics;

  public ComputerUseService(ScreenCapture capture, ComputerVisionModel model, ComputerInput input,
      UiStabilizer stabilizer, SafetyPolicy safety, BooleanSupplier cancelled,
      Consumer<ComputerProgress> events, int maxSteps, int historyLimit) {
    this(capture,model,input,stabilizer,safety,cancelled,events,maxSteps,historyLimit,new ComputerDiagnostics(null));
  }
  public ComputerUseService(ScreenCapture capture, ComputerVisionModel model, ComputerInput input,
      UiStabilizer stabilizer, SafetyPolicy safety, BooleanSupplier cancelled,
      Consumer<ComputerProgress> events, int maxSteps, int historyLimit, ComputerDiagnostics diagnostics) {
    if (maxSteps < 1 || maxSteps > 200 || historyLimit < 1 || historyLimit > 20)
      throw new IllegalArgumentException("Invalid loop limits");
    this.capture = capture; this.model = model; this.input = input; this.stabilizer = stabilizer;
    this.safety = safety; this.cancelled = cancelled; this.events = events;
    this.maxSteps = maxSteps; this.historyLimit = historyLimit;
    this.diagnostics = diagnostics;
  }

  public ComputerUseResult run(String goal) {
    if (!DESKTOP_BUSY.compareAndSet(false, true)) return new ComputerUseResult(BUSY, 0, "Another desktop workflow is active");
    try { return executeLoop(goal); } finally { DESKTOP_BUSY.set(false); }
  }

  private ComputerUseResult executeLoop(String goal) {
    if (goal == null || goal.isBlank() || goal.length() > 4000) throw new IllegalArgumentException("Invalid goal");
    var history = new ArrayList<String>();
    emit(new ComputerProgress(0, "started", null, null, null, null, null, null));
    final var diagnosticRun = beginDiagnostics();
    for (int step = 1; step <= maxSteps; step++) {
      var failure = CAPTURE_ERROR;
      try {
        checkCancelled();
        CapturedScreen screen = capture.captureScreen();
        checkCancelled();
        emit(new ComputerProgress(step, "observed", null, null, null, null, null,
            screen.displays().stream().map(d -> "displayId=" + d.geometry().id() + " image="
                + d.image().getWidth() + "x" + d.image().getHeight() + " scale=" + d.geometry().scaleX() + "," + d.geometry().scaleY())
                .collect(java.util.stream.Collectors.joining("; "))));
        final int diagnosticStep = step;
        recordDiagnostics(step, () -> diagnostics.observed(diagnosticRun,diagnosticStep,screen));
        checkCancelled();
        failure = MODEL_ERROR;
        ComputerAction action = model.decide(new ComputerObservation(goal, screen, history, step, maxSteps, diagnosticRun));
        checkCancelled();
        ActionValidator.validate(action, screen);
        var decision = progress(step, "decided", action);
        emit(decision);
        final var decidedAction = action;
        recordDiagnostics(step, () -> diagnostics.action(diagnosticRun,diagnosticStep,screen,decidedAction,"decided"));
        checkCancelled();
        if (action instanceof ComputerAction.Done done) return finish(DONE, step, done.reason());
        if (action instanceof ComputerAction.Failed failed) return finish(FAILED, step, failed.reason());
        if (!safety.allows(action)) return finish(SAFETY_BLOCKED, step, "Policy requires approval or prohibits action");
        if (decision.confidence() != null && decision.confidence() < .8) action = new ComputerAction.Uncertain("Low confidence; observe again");
        checkCancelled();
        failure = ACTION_ERROR;
        emit(progress(step, "action_started", action));
        checkCancelled();
        if (!(action instanceof ComputerAction.Wait) && !(action instanceof ComputerAction.Uncertain)) {
          input.execute(action, screen);
          final var dispatchedAction = action;
          recordDiagnostics(step, () -> diagnostics.action(diagnosticRun,diagnosticStep,screen,dispatchedAction,"dispatched"));
          var target = action instanceof ComputerAction.Click a ? a.target() : action instanceof ComputerAction.DoubleClick a ? a.target() : null;
          if (target != null) {
            var display = screen.display(target.displayId()); var point = display.desktopPoint(target);
            emit(new ComputerProgress(step,"input_coordinates",null,null,null,null,null,
                "displayId=" + display.geometry().id() + " normalized=(" + target.normalizedX() + "," + target.normalizedY()
                    + ") image=(" + target.x() + "," + target.y()
                    + ") robot=(" + point.x + "," + point.y + ")"));
          }
        }
        checkCancelled();
        emit(progress(step, "action_completed", action));
        checkCancelled();
        failure = STABILIZATION_ERROR;
        stabilizer.awaitAfter(action);
        checkCancelled();
        var summary = progress(step, "history", action);
        history.add(summary.action() + (summary.target() == null ? "" : " " + summary.target() + " executed image pixels=(" + summary.x() + "," + summary.y() + ")")
            + (summary.reason() == null ? "" : " " + summary.reason()) + "; dispatch only, goal not verified");
        if (history.size() > historyLimit) history.removeFirst();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return finish(CANCELLED, step, "Cancelled");
      } catch (java.util.concurrent.CancellationException error) {
        return finish(CANCELLED, step, "Cancelled");
      } catch (Exception error) {
        return finish(isCancelled() ? CANCELLED : failure, step,
            error instanceof InvalidComputerDecision ? error.getMessage() : error.getClass().getSimpleName());
      }
    }
    return finish(MAX_STEPS, maxSteps, "Observation limit reached");
  }

  private ComputerUseResult finish(ComputerUseResult.Status status, int step, String reason) {
    emit(new ComputerProgress(step, "finished", null, null, null, null, null,
        status.name() + (status == MODEL_ERROR ? ": " + reason : "")));
    return new ComputerUseResult(status, step, reason);
  }

  private void emit(ComputerProgress progress) {
    // Observability must never cause a dispatched operation to be retried.
    try { events.accept(progress); } catch (RuntimeException ignored) { }
  }
  private java.nio.file.Path beginDiagnostics() {
    try {
      var path = diagnostics.begin();
      if (path != null) emit(new ComputerProgress(0,"diagnostics",null,null,null,null,null,path.toString()));
      return path;
    } catch (Exception error) { diagnosticError(0,error); return null; }
  }
  @FunctionalInterface private interface DiagnosticWrite { void write() throws Exception; }
  private void recordDiagnostics(int step, DiagnosticWrite write) {
    try { write.write(); } catch (Exception error) { diagnosticError(step,error); }
  }
  private void diagnosticError(int step, Exception error) {
    emit(new ComputerProgress(step,"diagnostics_error",null,null,null,null,null,error.getClass().getSimpleName()));
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
    return new ComputerProgress(step, phase, action.getClass().getSimpleName(), target == null ? null :
        (target.displayId() == null ? "" : "[" + target.displayId() + "] ") + target.description(),
        target == null ? null : target.x(), target == null ? null : target.y(), confidence, reason);
  }

  private boolean isCancelled() { return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted(); }
  private void checkCancelled() {
    if (isCancelled()) throw new java.util.concurrent.CancellationException();
  }
}
