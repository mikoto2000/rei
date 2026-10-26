package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 音声入力からの依頼かどうかの複数の処理を調整する。
 *
 * <p>Capture and recognition have separate workers. JNI is released only after both really exit.
 */
public final class VoiceInputCoordinator implements AutoCloseable {
  /** 音声による対話における状態の選択肢。 */
  public enum State {
    /** 無効。 */
    OFF,
    /** 起動を開始している状態。 */
    STARTING,
    /** 発話を待ち受けている状態。 */
    LISTENING,
    /** 停止中。 */
    STOPPING,
    /** 失敗。 */
    FAILED,
    /** 終了済み。 */
    CLOSED
  }

  /** 利用条件の確認。 */
  private final Object guard = new Object();

  /** 取得記録。 */
  private final MicrophoneCaptureService capture;

  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void useLifeContext(dev.mikoto2000.rei.life.LifePolicy value) {
    life = value;
  }

  private boolean lifeAllowed(Run run, java.time.Instant since) {
    return life == null
        || (run.manual ? life.manualObservationAllowed(since) : life.observationAllowed(since));
  }

  private boolean lifeValid(Run run) {
    return life == null
        || (run.manual ? life.validManualObservation(run.lifeLease) : life.valid(run.lifeLease));
  }

  /** 音声処理の実行先を生成する窓口。 */
  private final VoiceBackendFactory backends;

  /** 受付。 */
  private final Consumer<ConversationInput> submit;

  /** イベント一覧。 */
  private final VoiceEventPublisher events;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 音声実行可否の判断。 */
  private final VoiceAudioGate audioGate;

  /** 音声処理の詳細設定。 */
  private final java.util.function.Supplier<VoiceAdvancedOptions> advanced;

  /** 選択中。 */
  private final java.util.function.Predicate<ConversationTarget> selected;

  /** 経過時間を測る時刻の取得元。 */
  private final java.util.function.LongSupplier ticks;

  /**
   * 音声による対話で受け渡す観測単位Stampを表す。
   *
   * @param nanos ナノ秒数
   * @param at 日時
   */
  private record FrameStamp(long nanos, java.time.Instant at) {}

  /**
   * 音声による対話で受け渡す完了を表す。
   *
   * @param input 入力
   * @param decision 判断結果
   * @param audioEpoch 音声基準時刻
   */
  private record Completed(
      ConversationInput input, VoiceCorrectionValidator.Decision decision, long audioEpoch) {}

  /** 音声処理の監督を行う実行先。 */
  private final ExecutorService supervisor =
      Executors.newSingleThreadExecutor(r -> daemon(r, "voice-supervisor"));

  /** 時刻による実行。 */
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voice-deadline"));

  /** 状態。 */
  private volatile State state = State.OFF;

  /** 活動中。 */
  private Run active;

  /** 終了済み。 */
  private boolean closed;

  /** 補正。 */
  private VoiceCorrectionService correction;

  /** 補正レビュー。 */
  private Consumer<ConversationInput> correctionReview;

  /**
   * 音声による対話に関する設定補正を処理する。
   *
   * <p>Configure once before starting; constructors preserve the original OFF behavior.
   */
  public void configureCorrection(
      VoiceCorrectionService service, Consumer<ConversationInput> review) {
    synchronized (guard) {
      if (active != null) throw new IllegalStateException("Voice already active");
      correction = Objects.requireNonNull(service);
      correctionReview = Objects.requireNonNull(review);
    }
  }

  /** 音声による対話に必要な実行を扱う。 */
  private static final class Run {
    /** 対象。 */
    final ConversationTarget target;

    /** 利用中の音声機器。 */
    final AudioDevice device;

    /** 設定。 */
    final VoiceSettings settings;

    /** 診断。 */
    final boolean diagnostic;

    /** 手動開始による勤務時間中の音声入力許可。このRunだけに適用する。 */
    final boolean manual;

    /** 直近観測単位。 */
    volatile FrameStamp lastFrame;

    /** 期限。 */
    volatile ScheduledFuture<?> deadline;

    /** 区間一覧。 */
    final VoiceInputQueue segments = new VoiceInputQueue();

    /** 対象ごとの世代番号。 */
    dev.mikoto2000.rei.life.LifeContext.Lease lifeLease;

    final ConcurrentMap<java.util.UUID, Long> epochs = new ConcurrentHashMap<>();

    /** 完了。 */
    final java.util.TreeMap<Long, Completed> completed = new java.util.TreeMap<>();

    /** 待機中。 */
    final java.util.LinkedHashMap<java.util.UUID, ConversationInput> pending =
        new java.util.LinkedHashMap<>();

    /** キャンセル済み。 */
    final java.util.Set<java.util.UUID> cancelled = new java.util.HashSet<>();

    /** 発生順序／次配信。 */
    long sequence, nextDelivery;

    /** 終了済み。 */
    final CountDownLatch exited = new CountDownLatch(2);

    /** 取得記録終了済み。 */
    final AtomicBoolean captureClosed = new AtomicBoolean();

    /** 取得記録解放済み。 */
    final CountDownLatch captureReleased = new CountDownLatch(1);

    /** 停止。 */
    volatile boolean stop;

    /** 失敗。 */
    volatile boolean failed;

    /** バックエンド。 */
    volatile VoiceBackend backend;

    /** 取得元。 */
    volatile MicrophoneCaptureService.FrameSource source;

    /** 取得記録スレッド／認識スレッド／起動時スレッド。 */
    volatile Thread captureThread, recognitionThread, startupThread;

    /** 指定された値や利用先を使って、Runを初期化する。 */
    Run(
        ConversationTarget target,
        AudioDevice device,
        VoiceSettings settings,
        boolean diagnostic,
        boolean manual) {
      this.target = target;
      this.device = device;
      this.settings = settings;
      this.diagnostic = diagnostic;
      this.manual = manual;
    }
  }

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public VoiceInputCoordinator(
      MicrophoneCaptureService capture,
      VoiceBackendFactory backends,
      Consumer<ConversationInput> submit,
      VoiceEventPublisher events,
      Clock clock) {
    this(capture, backends, submit, events, clock, target -> true, System::nanoTime);
  }

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public VoiceInputCoordinator(
      MicrophoneCaptureService capture,
      VoiceBackendFactory backends,
      Consumer<ConversationInput> submit,
      VoiceEventPublisher events,
      Clock clock,
      java.util.function.Predicate<ConversationTarget> selected,
      java.util.function.LongSupplier ticks) {
    this(capture, backends, submit, events, clock, selected, ticks, new VoiceAudioGate(ticks));
  }

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public VoiceInputCoordinator(
      MicrophoneCaptureService capture,
      VoiceBackendFactory backends,
      Consumer<ConversationInput> submit,
      VoiceEventPublisher events,
      Clock clock,
      java.util.function.Predicate<ConversationTarget> selected,
      java.util.function.LongSupplier ticks,
      VoiceAudioGate audioGate) {
    this(
        capture,
        backends,
        submit,
        events,
        clock,
        selected,
        ticks,
        audioGate,
        VoiceAdvancedOptions::defaults);
  }

  /** 指定された値や利用先を使って、VoiceInputCoordinatorを初期化する。 */
  public VoiceInputCoordinator(
      MicrophoneCaptureService capture,
      VoiceBackendFactory backends,
      Consumer<ConversationInput> submit,
      VoiceEventPublisher events,
      Clock clock,
      java.util.function.Predicate<ConversationTarget> selected,
      java.util.function.LongSupplier ticks,
      VoiceAudioGate audioGate,
      java.util.function.Supplier<VoiceAdvancedOptions> advanced) {
    this.advanced = Objects.requireNonNull(advanced);
    this.audioGate = Objects.requireNonNull(audioGate);
    this.selected = Objects.requireNonNull(selected);
    this.ticks = Objects.requireNonNull(ticks);
    this.capture = Objects.requireNonNull(capture);
    this.backends = Objects.requireNonNull(backends);
    this.submit = Objects.requireNonNull(submit);
    this.events = Objects.requireNonNull(events);
    this.clock = Objects.requireNonNull(clock);
  }

  /** 音声による対話で用いる常駐を返す。 */
  private static Thread daemon(Runnable work, String name) {
    var thread = new Thread(work, name);
    thread.setDaemon(true);
    return thread;
  }

  /** 音声による対話で用いる状態を返す。 */
  public State state() {
    return state;
  }

  /**
   * 音声による対話に必要な起動時を扱う。
   *
   * <p>A waiter can only cancel the Run it started, even after an OFF/ON replacement.
   */
  public interface Startup {
    /** 必要な状態が整ってから先へ進むため、起動時を待つ。 */
    State await(java.time.Duration timeout);
  }

  /** 必要な状態が整ってから先へ進むため、起動時を待つ。 */
  public State awaitStartup(java.time.Duration timeout) {
    Run starting;
    synchronized (guard) {
      starting = active;
    }
    return awaitStartup(starting, timeout);
  }

  /** 必要な状態が整ってから先へ進むため、起動時を待つ。 */
  private State awaitStartup(Run starting, java.time.Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      synchronized (guard) {
        while (active == starting && state == State.STARTING) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) break;
          TimeUnit.NANOSECONDS.timedWait(guard, remaining);
        }
        if (active != starting) return State.OFF;
        if (state != State.STARTING) return state;
      }
    } catch (InterruptedException e) {
      off(starting);
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Voice initialization interrupted");
    }
    off(starting);
    throw new IllegalStateException("Voice initialization timed out; waiting for native cleanup");
  }

  /** 音声による対話で用いる待機中区間一覧を返す。 */
  public int queuedSegments() {
    synchronized (guard) {
      return active == null ? 0 : active.segments.size();
    }
  }

  /** 音声による対話で用いる待機中補正入力一覧を返す。 */
  public java.util.List<ConversationInput> pendingCorrectionInputs() {
    synchronized (guard) {
      if (active == null || active.stop || !selected(active)) return java.util.List.of();
      return active.pending.values().stream()
          .filter(input -> !active.cancelled.contains(input.inputId()))
          .toList();
    }
  }

  /** 利用者が不要とした実行を止めるため、待機中補正をキャンセルする。 */
  public boolean cancelPendingCorrection(java.util.UUID id) {
    synchronized (guard) {
      var run = active;
      if (run == null || !run.pending.containsKey(id) || !run.cancelled.add(id)) return false;
      // Mark before transport cancellation: a completion already waiting for the guard must also be
      // discarded.
      if (correction != null) correction.cancel(id);
      return true;
    }
  }

  /** 音声による対話に関する変更を処理する。 */
  private void change(State next) {
    if (state == next) return;
    state = next;
    guard.notifyAll();
    events.publish(VoiceEventPublisher.Type.STATE_CHANGED, next.name());
  }

  /** 要求された処理を開始する。 */
  public Startup start(ConversationTarget target, AudioDevice device, VoiceSettings settings) {
    return start(target, device, settings, false, false);
  }

  /** 明示的な /voice on による開始。勤務時間中の入力をこのRunに限り許可する。 */
  public Startup startManual(
      ConversationTarget target, AudioDevice device, VoiceSettings settings) {
    return start(target, device, settings, false, true);
  }

  /** 要求された処理を利用できるよう、診断を開始する。 */
  public Startup startDiagnostic(
      ConversationTarget target, AudioDevice device, VoiceSettings settings) {
    return start(target, device, settings, true, false);
  }

  /** 要求された処理を開始する。 */
  private Startup start(
      ConversationTarget target,
      AudioDevice device,
      VoiceSettings settings,
      boolean diagnostic,
      boolean manual) {
    if (target == null || device == null || settings == null)
      throw new IllegalArgumentException("Select a microphone and target first");
    synchronized (guard) {
      if (closed || active != null)
        throw new IllegalStateException("Voice is already active or closed");
      var run = new Run(target, device, settings, diagnostic, manual);
      active = run;
      change(State.STARTING);
      supervisor.execute(() -> supervise(run));
      return timeout -> awaitStartup(run, timeout);
    }
  }

  /** 音声による対話に関する無効を処理する。 */
  public void off() {
    off(null);
  }

  /** 音声による対話に関する無効を処理する。 */
  private void off(Run expected) {
    Run run;
    synchronized (guard) {
      run = active;
      if (expected != null && run != expected) return;
      if (run == null) {
        if (!closed) change(State.OFF);
        return;
      }
      // Explicit OFF acknowledges a startup failure; cleanup errors still emit their own failure.
      run.failed = false;
      run.stop = true;
      run.segments.clear();
      run.epochs.clear();
      change(State.STOPPING);
    }
    stopWorkers(run);
  }

  /** 処理の継続を終えるため、実行の担当一覧を停止する。 */
  private void stopWorkers(Run run) {
    synchronized (guard) {
      if (run.startupThread != null && run.startupThread != Thread.currentThread())
        run.startupThread.interrupt();
    }
    if (run.captureThread != null) run.captureThread.interrupt();
    if (run.recognitionThread != null) run.recognitionThread.interrupt();
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (run.source != null && run.captureClosed.compareAndSet(false, true)) {
      // A broken audio driver must not hold the Shell caller. Only one Run can remain
      // active, and backend cleanup still waits for both workers and this real release.
      daemon(
              () -> {
                try {
                  run.source.close();
                } catch (RuntimeException | LinkageError e) {
                  run.failed = true;
                  events.publish(VoiceEventPublisher.Type.RELEASE_FAILED, "capture");
                } finally {
                  run.captureReleased.countDown();
                }
              },
              "voice-microphone-release")
          .start();
    }
  }

  /** 音声による対話に関するfailを処理する。 */
  private void fail(Run run, VoiceEventPublisher.Type code) {
    stop(run, code, true);
  }

  /** 実行中の処理を停止する。 */
  private void stop(Run run, VoiceEventPublisher.Type code, boolean failed) {
    synchronized (guard) {
      if (run.stop) return;
      run.failed |= failed;
      run.stop = true;
      run.segments.clear();
      run.epochs.clear();
      change(State.STOPPING);
    }
    events.publish(code, "voice stopped");
    stopWorkers(run);
  }

  /** 音声による対話において、選択中に該当するか判定する。 */
  private boolean selected(Run run) {
    try {
      return selected.test(run.target);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /** 音声による対話において、古い状態に該当するか判定する。 */
  private boolean stale(Run run) {
    var stamp = run.lastFrame;
    if (stamp == null) return false;
    long wall = java.time.Duration.between(stamp.at(), clock.instant()).toMillis();
    return ticks.getAsLong() - stamp.nanos() > TimeUnit.SECONDS.toNanos(5)
        || wall > 5000
        || wall < -5000;
  }

  /**
   * 音声による対話に関するdeliverを処理する。
   *
   * <p>Preserve ASR order even when timeout/queue fallback completes before an earlier LLM. Raw
   * stop remains immediate.
   */
  private void deliver(Run run, Completed completed) {
    run.pending.remove(completed.input().inputId());
    if (run.cancelled.remove(completed.input().inputId())) return;
    if (run.stop
        || active != run
        || !selected(run)
        || !lifeAllowed(run, completed.input().createdAt())
        || !lifeValid(run)
        || !audioGate.allowed(completed.audioEpoch())
        || completed.decision().reason().equals("cancelled")) return;
    try {
      audioGate.ifAllowed(
          completed.audioEpoch(),
          () -> {
            var input = completed.input();
            var decision = completed.decision();
            var accepted =
                new ConversationInput(
                    input.inputId(),
                    InputSource.VOICE,
                    input.target(),
                    decision.text(),
                    input.createdAt());
            if (decision.confirmationRequired() && correctionReview != null)
              correctionReview.accept(accepted);
            else submit.accept(accepted);
          });
    } catch (RuntimeException rejected) {
      events.publish(VoiceEventPublisher.Type.INPUT_REJECTED, "input rejected");
    }
  }

  /** 音声による対話に関するsuperviseを処理する。 */
  private void supervise(Run run) {
    boolean captureStarted = false, recognitionStarted = false;
    try {
      if (!selected(run)) {
        stop(run, VoiceEventPublisher.Type.TARGET_CHANGED, false);
        return;
      }
      synchronized (guard) {
        if (run.stop) return;
        run.startupThread = Thread.currentThread();
      }
      try {
        if (!lifeAllowed(run, clock.instant())) {
          fail(run, VoiceEventPublisher.Type.PRIVACY_BLOCKED);
          return;
        }
        run.backend = backends.open(run.settings);
      } finally {
        synchronized (guard) {
          run.startupThread = null;
        }
      }
      if (run.stop) return;
      if (!selected(run)) {
        stop(run, VoiceEventPublisher.Type.TARGET_CHANGED, false);
        return;
      }
      if (!lifeAllowed(run, clock.instant())) {
        fail(run, VoiceEventPublisher.Type.PRIVACY_BLOCKED);
        return;
      }
      run.lifeLease = life == null ? null : life.begin();
      run.source = capture.open(run.device);
      if (run.source == null) throw new IllegalStateException("Capture source unavailable");
      synchronized (guard) {
        if (run.stop) return;
        run.captureThread = daemon(() -> captureLoop(run), "voice-capture");
        run.recognitionThread = daemon(() -> recognizeLoop(run), "voice-recognition");
        run.lastFrame = new FrameStamp(ticks.getAsLong(), clock.instant());
        run.captureThread.start();
        captureStarted = true;
        run.recognitionThread.start();
        recognitionStarted = true;
        change(State.LISTENING);
        if (run.diagnostic)
          run.deadline = timer.schedule(() -> stopDiagnostic(run), 20, TimeUnit.SECONDS);
      }
      while (!run.exited.await(250, TimeUnit.MILLISECONDS)) {
        if (!run.stop && !selected(run)) {
          stop(run, VoiceEventPublisher.Type.TARGET_CHANGED, false);
          continue;
        }
        if (!run.stop) {
          try {
            run.source.checkHealth();
          } catch (Exception healthFailure) {
            fail(run, VoiceEventPublisher.Type.DEVICE_CHANGED);
            continue;
          }
          if (stale(run)) {
            var stamp = run.lastFrame;
            fail(
                run,
                ticks.getAsLong() - stamp.nanos() > TimeUnit.SECONDS.toNanos(5)
                    ? VoiceEventPublisher.Type.CAPTURE_FAILED
                    : VoiceEventPublisher.Type.CAPTURE_RESUMED);
          }
        }
      }
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      fail(run, VoiceEventPublisher.Type.BACKEND_FAILED);
    } catch (Exception | LinkageError e) {
      fail(
          run,
          run.backend == null
              ? VoiceEventPublisher.Type.BACKEND_FAILED
              : VoiceEventPublisher.Type.CAPTURE_FAILED);
    } finally {
      run.stop = true;
      if (run.deadline != null) run.deadline.cancel(false);
      if (!captureStarted) run.exited.countDown();
      if (!recognitionStarted) run.exited.countDown();
      stopWorkers(run);
      boolean interrupted = Thread.interrupted();
      while (run.exited.getCount() != 0) {
        try {
          run.exited.await();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (run.source != null) {
        while (run.captureReleased.getCount() != 0) {
          try {
            run.captureReleased.await();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      }
      run.segments.clear();
      run.epochs.clear();
      if (run.backend != null) {
        try {
          run.backend.close();
        } catch (RuntimeException | LinkageError e) {
          run.failed = true;
          events.publish(VoiceEventPublisher.Type.RELEASE_FAILED, "backend");
        }
      }
      synchronized (guard) {
        if (active == run) {
          active = null;
          change(closed ? State.CLOSED : run.failed ? State.FAILED : State.OFF);
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  /** 後から状況を確認できるよう、繰り返しを記録する。 */
  private void captureLoop(Run run) {
    var assembler = new SpeechSegmentAssembler(run.settings, clock);
    long previousEpoch = audioGate.epoch();
    long vadNanos = 0;
    try {
      while (!run.stop) {
        if (!lifeAllowed(run, clock.instant()) || !lifeValid(run)) {
          fail(run, VoiceEventPublisher.Type.PRIVACY_BLOCKED);
          break;
        }
        var frameStarted = clock.instant();
        var frame = run.source.readFrame();
        if (!lifeAllowed(run, frameStarted)) {
          fail(run, VoiceEventPublisher.Type.PRIVACY_BLOCKED);
          break;
        }
        if (run.stop) break;
        if (frame == null) {
          fail(run, VoiceEventPublisher.Type.CAPTURE_FAILED);
          break;
        }
        if (stale(run)) {
          fail(run, VoiceEventPublisher.Type.CAPTURE_RESUMED);
          break;
        }
        VoicePcm.validateFrame(frame);
        run.lastFrame = new FrameStamp(ticks.getAsLong(), clock.instant());
        long epoch = audioGate.epoch();
        if (epoch != previousEpoch || audioGate.suppressed()) {
          assembler.reset();
          run.segments.clear();
          run.epochs.clear();
          previousEpoch = epoch;
          vadNanos = 0;
          if (audioGate.suppressed()) {
            run.backend.vad().probability(new float[VoiceSettings.WINDOW]);
            continue;
          }
        }
        long vadStart = ticks.getAsLong();
        float probability = run.backend.vad().probability(frame);
        vadNanos += Math.max(0, ticks.getAsLong() - vadStart);
        var decision = assembler.accept(frame, probability);
        switch (decision.reason()) {
          case COMPLETED -> {
            events.publish(VoiceEventPublisher.Type.ASR_TIMING, "vad_ms=" + vadNanos / 1_000_000);
            vadNanos = 0;
            if (!run.stop && audioGate.allowed(epoch)) {
              var segment = decision.segment();
              run.epochs.put(segment.id(), epoch);
              if (!run.segments.offer(segment)) {
                run.epochs.remove(segment.id());
                events.publish(VoiceEventPublisher.Type.SEGMENT_QUEUE_FULL, "segment dropped");
              }
            }
          }
          case SHORT_DROPPED -> {
            vadNanos = 0;
            events.publish(VoiceEventPublisher.Type.SHORT_DROPPED, "segment dropped");
          }
          case MAX_DROPPED -> {
            vadNanos = 0;
            events.publish(VoiceEventPublisher.Type.MAX_DROPPED, "unfinished segment dropped");
          }
          default -> {}
        }
      }
    } catch (InterruptedException e) {
      if (!run.stop) fail(run, VoiceEventPublisher.Type.CAPTURE_FAILED);
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    } catch (Exception | LinkageError e) {
      fail(run, VoiceEventPublisher.Type.CAPTURE_FAILED);
    } finally {
      assembler.reset();
      run.exited.countDown();
    }
  }

  /** 音声による対話に関するrecognize繰り返しを処理する。 */
  private void recognizeLoop(Run run) {
    var wakeGate = new VoiceWakeGate(clock);
    try {
      while (!run.stop) {
        var segment = run.segments.poll(200, TimeUnit.MILLISECONDS);
        if (segment == null) continue;
        var epoch = run.epochs.remove(segment.id());
        if (epoch == null
            || !audioGate.allowed(epoch)
            || !lifeAllowed(run, segment.createdAt())
            || !lifeValid(run)) continue;
        long asrStarted = ticks.getAsLong();
        String asrOriginal = run.backend.recognizer().recognize(segment);
        var result = SpeechResultFilter.filter(asrOriginal);
        events.publish(
            VoiceEventPublisher.Type.ASR_TIMING,
            "asr_ms=" + Math.max(0, (ticks.getAsLong() - asrStarted) / 1_000_000));
        if (!selected(run)) {
          stop(run, VoiceEventPublisher.Type.TARGET_CHANGED, false);
          continue;
        }
        if (stale(run)) {
          fail(run, VoiceEventPublisher.Type.CAPTURE_RESUMED);
          continue;
        }
        ConversationInput[] prepared = new ConversationInput[1];
        synchronized (guard) {
          if (run.stop
              || active != run
              || !audioGate.allowed(epoch)
              || !lifeAllowed(run, segment.createdAt())
              || !lifeValid(run)) continue;
          if (result.isEmpty()) {
            events.publish(VoiceEventPublisher.Type.RESULT_REJECTED, "recognition dropped");
            continue;
          }
          if (run.diagnostic) {
            events.publish(VoiceEventPublisher.Type.DIAGNOSTIC_RESULT, asrOriginal);
            continue;
          }
          try {
            audioGate.ifAllowed(
                epoch,
                () -> {
                  var acceptedText = wakeGate.filter(result.get(), run.target, advanced.get());
                  if (acceptedText.isEmpty()) {
                    events.publish(VoiceEventPublisher.Type.RESULT_REJECTED, "wake gate");
                    return;
                  }
                  prepared[0] =
                      new ConversationInput(
                          segment.id(),
                          InputSource.VOICE,
                          run.target,
                          acceptedText.get(),
                          segment.createdAt());
                });
          } catch (RuntimeException e) {
            events.publish(VoiceEventPublisher.Type.INPUT_REJECTED, "input rejected");
          }
        }
        if (prepared[0] != null) {
          var input = prepared[0];
          var once = new AtomicBoolean();
          boolean immediateStop = VoiceCorrectionValidator.controlKey(input.text()).equals("実行を停止");
          long sequence;
          synchronized (guard) {
            if (!immediateStop && run.sequence - run.nextDelivery >= 64) {
              events.publish(VoiceEventPublisher.Type.INPUT_REJECTED, "delivery capacity");
              continue;
            }
            sequence = immediateStop ? -1 : run.sequence++;
            if (!immediateStop) run.pending.put(input.inputId(), input);
          }
          java.util.function.Consumer<VoiceCorrectionValidator.Decision> complete =
              decision -> {
                synchronized (guard) {
                  // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
                  if (!once.compareAndSet(false, true) || run.stop || active != run) return;
                  var item = new Completed(input, decision, epoch);
                  if (immediateStop) {
                    deliver(run, item);
                    return;
                  }
                  run.completed.put(sequence, item);
                  while (run.completed.containsKey(run.nextDelivery))
                    deliver(run, run.completed.remove(run.nextDelivery++));
                }
              };
          // LLM/context retrieval must not hold capture/lifecycle locks. Control is classified from
          // raw ASR.
          if (correction == null || VoiceCorrectionValidator.controlOrReply(input.text()))
            complete.accept(new VoiceCorrectionValidator.Decision(input.text(), "bypassed", false));
          else
            correction.correct(
                input,
                asrOriginal,
                () ->
                    !run.stop
                        && selected(run)
                        && audioGate.allowed(epoch)
                        && lifeAllowed(run, input.createdAt())
                        && lifeValid(run),
                complete);
        }
      }
    } catch (InterruptedException e) {
      if (!run.stop) fail(run, VoiceEventPublisher.Type.RECOGNITION_FAILED);
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    } catch (Exception | LinkageError e) {
      fail(run, VoiceEventPublisher.Type.RECOGNITION_FAILED);
    } finally {
      run.exited.countDown();
    }
  }

  /** 処理の継続を終えるため、診断を停止する。 */
  private void stopDiagnostic(Run run) {
    synchronized (guard) {
      if (active != run || run.stop) return;
      run.stop = true;
      run.segments.clear();
      run.epochs.clear();
      change(State.STOPPING);
    }
    stopWorkers(run);
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    synchronized (guard) {
      if (closed) return;
      closed = true;
    }
    off();
    if (correction != null) correction.cancelAll();
    timer.shutdownNow();
    supervisor.shutdown();
    try {
      supervisor.awaitTermination(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
    synchronized (guard) {
      if (active == null) change(State.CLOSED);
    }
  }
}
