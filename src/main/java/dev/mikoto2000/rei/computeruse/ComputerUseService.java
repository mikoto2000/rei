package dev.mikoto2000.rei.computeruse;

import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.ACTION_ERROR;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.BUSY;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.CANCELLED;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.CAPTURE_ERROR;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.DONE;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.FAILED;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.MAX_STEPS;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.MODEL_ERROR;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.SAFETY_BLOCKED;
import static dev.mikoto2000.rei.computeruse.ComputerUseResult.Status.STABILIZATION_ERROR;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 画面操作利用のユースケースを実現する。 */
public final class ComputerUseService {
  /** デスクトップ使用中。 */
  private static final java.util.concurrent.atomic.AtomicBoolean DESKTOP_BUSY =
      new java.util.concurrent.atomic.AtomicBoolean();

  /** 取得記録。 */
  private final ScreenCapture capture;

  /** モデル。 */
  private final ComputerVisionModel model;

  /** 入力。 */
  private final ComputerInput input;

  /** 画面の変化が落ち着いたか確認する窓口。 */
  private final UiStabilizer stabilizer;

  /** 画面操作の許可条件を判断する基準。 */
  private final SafetyPolicy safety;

  /** キャンセル済み。 */
  private final BooleanSupplier cancelled;

  /** イベント一覧。 */
  private final Consumer<ComputerProgress> events;

  /** 最大手順一覧。 */
  private final int maxSteps;

  /** 履歴上限。 */
  private final int historyLimit;

  /** 診断情報。 */
  private final ComputerDiagnostics diagnostics;

  /** 操作対象となる前面ウィンドウの取得元。 */
  private final Supplier<String> focus;

  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ComputerUseService(
      ScreenCapture capture,
      ComputerVisionModel model,
      ComputerInput input,
      UiStabilizer stabilizer,
      SafetyPolicy safety,
      BooleanSupplier cancelled,
      Consumer<ComputerProgress> events,
      int maxSteps,
      int historyLimit) {
    this(
        capture,
        model,
        input,
        stabilizer,
        safety,
        cancelled,
        events,
        maxSteps,
        historyLimit,
        new ComputerDiagnostics(null));
  }

  /** 画面操作の支援を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ComputerUseService(
      ScreenCapture capture,
      ComputerVisionModel model,
      ComputerInput input,
      UiStabilizer stabilizer,
      SafetyPolicy safety,
      BooleanSupplier cancelled,
      Consumer<ComputerProgress> events,
      int maxSteps,
      int historyLimit,
      ComputerDiagnostics diagnostics) {
    this(
        capture,
        model,
        input,
        stabilizer,
        safety,
        cancelled,
        events,
        maxSteps,
        historyLimit,
        diagnostics,
        () -> WindowsFocusProbe.UNKNOWN);
  }

  /** 指定された値や利用先を使って、ComputerUseServiceを初期化する。 */
  public ComputerUseService(
      ScreenCapture capture,
      ComputerVisionModel model,
      ComputerInput input,
      UiStabilizer stabilizer,
      SafetyPolicy safety,
      BooleanSupplier cancelled,
      Consumer<ComputerProgress> events,
      int maxSteps,
      int historyLimit,
      ComputerDiagnostics diagnostics,
      Supplier<String> focus) {
    if (maxSteps < 1 || maxSteps > 200 || historyLimit < 1 || historyLimit > 20)
      throw new IllegalArgumentException("Invalid loop limits");
    this.capture = capture;
    this.model = model;
    this.input = input;
    this.stabilizer = stabilizer;
    this.safety = safety;
    this.cancelled = cancelled;
    this.events = events;
    this.maxSteps = maxSteps;
    this.historyLimit = historyLimit;
    this.diagnostics = diagnostics;
    this.focus = java.util.Objects.requireNonNull(focus);
  }

  /** 画面操作の支援の操作を実行する。 */
  public ComputerUseResult run(String goal) {
    if (!DESKTOP_BUSY.compareAndSet(false, true))
      return new ComputerUseResult(BUSY, 0, "Another desktop workflow is active");
    try {
      return executeLoop(goal);
    } finally {
      DESKTOP_BUSY.set(false);
    }
  }

  /** 依頼された操作を進めるため、繰り返しを実行する。 */
  private ComputerUseResult executeLoop(String goal) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (goal == null || goal.isBlank() || goal.length() > 4000)
      throw new IllegalArgumentException("Invalid goal");
    var history = new ArrayList<String>();
    var textInputs = new TextInputHistory();
    emit(new ComputerProgress(0, "started", null, null, null, null, null, null));
    final var diagnosticRun = beginDiagnostics();
    for (int step = 1; step <= maxSteps; step++) {
      var failure = CAPTURE_ERROR;
      try {
        checkCancelled();
        CapturedScreen screen = capture.captureScreen();
        checkCancelled();
        emit(
            new ComputerProgress(
                step,
                "observed",
                null,
                null,
                null,
                null,
                null,
                screen.displays().stream()
                    .map(
                        d ->
                            "displayId="
                                + d.geometry().id()
                                + " image="
                                + d.image().getWidth()
                                + "x"
                                + d.image().getHeight()
                                + " scale="
                                + d.geometry().scaleX()
                                + ","
                                + d.geometry().scaleY())
                    .collect(java.util.stream.Collectors.joining("; "))));
        final int diagnosticStep = step;
        recordDiagnostics(step, () -> diagnostics.observed(diagnosticRun, diagnosticStep, screen));
        checkCancelled();
        failure = MODEL_ERROR;
        String focusState = focus.get();
        checkCancelled();
        emit(
            new ComputerProgress(step, "focus_observed", null, null, null, null, null, focusState));
        recordDiagnostics(
            step,
            () -> {
              if (diagnosticRun != null)
                java.nio.file.Files.writeString(
                    java.nio.file.Files.createDirectories(
                            diagnosticRun.resolve("step-%03d".formatted(diagnosticStep)))
                        .resolve("focus.json"),
                    focusState);
            });
        ComputerAction action =
            model.decide(
                new ComputerObservation(
                    goal, screen, history, step, maxSteps, diagnosticRun, focusState));
        checkCancelled();
        ActionValidator.validate(action, screen);
        var decision = progress(step, "decided", action);
        emit(decision);
        final var decidedAction = action;
        recordDiagnostics(
            step,
            () ->
                diagnostics.action(
                    diagnosticRun, diagnosticStep, screen, decidedAction, "decided"));
        checkCancelled();
        if (action instanceof ComputerAction.Done done) return finish(DONE, step, done.reason());
        if (action instanceof ComputerAction.Failed failed)
          return finish(FAILED, step, failed.reason());
        if (!safety.allows(action))
          return finish(SAFETY_BLOCKED, step, "Policy requires approval or prohibits action");
        String typingFocus = focusState;
        if (action instanceof ComputerAction.TypeText typed) {
          typingFocus = focus.get();
          checkCancelled();
          String observedFocus = typingFocus;
          recordDiagnostics(
              step,
              () -> {
                if (diagnosticRun != null)
                  java.nio.file.Files.writeString(
                      diagnosticRun
                          .resolve("step-%03d".formatted(diagnosticStep))
                          .resolve("typing-focus.json"),
                      observedFocus);
              });
          textInputs.check(typed.text(), typingFocus);
        }
        if (decision.confidence() != null && decision.confidence() < .8)
          action = new ComputerAction.Uncertain("Low confidence; observe again");
        checkCancelled();
        failure = ACTION_ERROR;
        emit(progress(step, "action_started", action));
        checkCancelled();
        if (!(action instanceof ComputerAction.Wait)
            && !(action instanceof ComputerAction.Uncertain)) {
          input.execute(action, screen);
          if (action instanceof ComputerAction.TypeText typed)
            textInputs.dispatched(typed.text(), typingFocus);
          final var dispatchedAction = action;
          recordDiagnostics(
              step,
              () ->
                  diagnostics.action(
                      diagnosticRun, diagnosticStep, screen, dispatchedAction, "dispatched"));
          var target =
              action instanceof ComputerAction.Click a
                  ? a.target()
                  : action instanceof ComputerAction.DoubleClick a ? a.target() : null;
          if (target != null) {
            var display = screen.display(target.displayId());
            var point = display.desktopPoint(target);
            emit(
                new ComputerProgress(
                    step,
                    "input_coordinates",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "displayId="
                        + display.geometry().id()
                        + " normalized=("
                        + target.normalizedX()
                        + ","
                        + target.normalizedY()
                        + ") image=("
                        + target.x()
                        + ","
                        + target.y()
                        + ") robot=("
                        + point.x
                        + ","
                        + point.y
                        + ")"));
          }
        }
        checkCancelled();
        emit(progress(step, "action_completed", action));
        checkCancelled();
        failure = STABILIZATION_ERROR;
        stabilizer.awaitAfter(action);
        checkCancelled();
        var summary = progress(step, "history", action);
        history.add(
            summary.action()
                + (summary.target() == null
                    ? ""
                    : " "
                        + summary.target()
                        + " executed image pixels=("
                        + summary.x()
                        + ","
                        + summary.y()
                        + ")")
                + ActionParameters.history(action)
                + (action instanceof ComputerAction.TypeText typed
                    ? " " + TextInputHistory.summary(typed.text(), typingFocus)
                    : "")
                + (summary.reason() == null ? "" : " " + summary.reason())
                + "; dispatch only, goal not verified");
        if (history.size() > historyLimit) history.removeFirst();
      } catch (InterruptedException error) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        return finish(CANCELLED, step, "Cancelled");
      } catch (java.util.concurrent.CancellationException error) {
        return finish(CANCELLED, step, "Cancelled");
      } catch (Exception error) {
        if (failure == MODEL_ERROR && !isCancelled())
          org.slf4j.LoggerFactory.getLogger(ComputerUseService.class)
              .error("Computer Use task failed during screenshot analysis: step={}", step, error);
        return finish(
            isCancelled() ? CANCELLED : failure,
            step,
            error instanceof InvalidComputerDecision
                ? error.getMessage()
                : error.getClass().getSimpleName());
      }
    }
    return finish(MAX_STEPS, maxSteps, "Observation limit reached");
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  private ComputerUseResult finish(ComputerUseResult.Status status, int step, String reason) {
    emit(
        new ComputerProgress(
            step,
            "finished",
            null,
            null,
            null,
            null,
            null,
            status.name() + (status == MODEL_ERROR ? ": " + reason : "")));
    return new ComputerUseResult(status, step, reason);
  }

  /** 画面操作の支援に関する通知を処理する。 */
  private void emit(ComputerProgress progress) {
    // Observability must never cause a dispatched operation to be retried.
    try {
      events.accept(progress);
    } catch (RuntimeException ignored) {
    }
  }

  /** 画面操作の支援で用いる開始診断情報を返す。 */
  private java.nio.file.Path beginDiagnostics() {
    try {
      var path = diagnostics.begin();
      if (path != null)
        emit(new ComputerProgress(0, "diagnostics", null, null, null, null, null, path.toString()));
      return path;
    } catch (Exception error) {
      diagnosticError(0, error);
      return null;
    }
  }

  /** 画面操作の支援に必要な診断書き込みを扱う。 */
  @FunctionalInterface
  private interface DiagnosticWrite {
    /** 指定された内容を書き込み先へ反映する。 */
    void write() throws Exception;
  }

  /** 後から経緯や使用量を確認できるよう、診断情報を記録する。 */
  private void recordDiagnostics(int step, DiagnosticWrite write) {
    try {
      write.write();
    } catch (Exception error) {
      diagnosticError(step, error);
    }
  }

  /** 画面操作の支援に関する診断エラーを処理する。 */
  private void diagnosticError(int step, Exception error) {
    emit(
        new ComputerProgress(
            step,
            "diagnostics_error",
            null,
            null,
            null,
            null,
            null,
            error.getClass().getSimpleName()));
  }

  /** 画面操作の支援で用いる進捗を返す。 */
  static ComputerProgress progress(int step, String phase, ComputerAction action) {
    ComputerAction.Target target = null;
    Double confidence = null;
    String reason = null;
    if (action instanceof ComputerAction.Click a) {
      target = a.target();
      confidence = a.confidence();
    }
    if (action instanceof ComputerAction.DoubleClick a) {
      target = a.target();
      confidence = a.confidence();
    }
    if (action instanceof ComputerAction.Uncertain a) reason = a.reason();
    if (action instanceof ComputerAction.Done a) reason = a.reason();
    if (action instanceof ComputerAction.Failed a) reason = a.reason();
    return new ComputerProgress(
        step,
        phase,
        action.getClass().getSimpleName(),
        target == null
            ? null
            : (target.displayId() == null ? "" : "[" + target.displayId() + "] ")
                + target.description(),
        target == null ? null : target.x(),
        target == null ? null : target.y(),
        confidence,
        reason);
  }

  /** 画面操作の支援において、キャンセル済みかどうかを判定する。 */
  private boolean isCancelled() {
    return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
  }

  /** 後続の操作を進められるか判断するため、キャンセル済みを確認する。 */
  private void checkCancelled() {
    if (isCancelled()) throw new java.util.concurrent.CancellationException();
  }
}
