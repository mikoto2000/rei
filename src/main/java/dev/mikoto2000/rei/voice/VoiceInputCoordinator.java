package dev.mikoto2000.rei.voice;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import dev.mikoto2000.rei.application.input.*;

/** Capture and recognition have separate workers. JNI is released only after both really exit. */
public final class VoiceInputCoordinator implements AutoCloseable {
  public enum State { OFF, STARTING, LISTENING, STOPPING, FAILED, CLOSED }
  private final Object guard = new Object();
  private final MicrophoneCaptureService capture;
  private final VoiceBackendFactory backends;
  private final Consumer<ConversationInput> submit;
  private final VoiceEventPublisher events;
  private final Clock clock;
  private final VoiceAudioGate audioGate;
  private final java.util.function.Supplier<VoiceAdvancedOptions> advanced;
  private final java.util.function.Predicate<ConversationTarget> selected;
  private final java.util.function.LongSupplier ticks;
  private record FrameStamp(long nanos,java.time.Instant at) {}
  private final ExecutorService supervisor = Executors.newSingleThreadExecutor(r -> daemon(r, "voice-supervisor"));
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voice-deadline"));
  private volatile State state = State.OFF;
  private Run active;
  private boolean closed;

  private static final class Run {
    final ConversationTarget target;
    final AudioDevice device;
    final VoiceSettings settings;
    final boolean diagnostic;
    volatile FrameStamp lastFrame;
    volatile ScheduledFuture<?> deadline;
    final VoiceInputQueue segments = new VoiceInputQueue();
    final ConcurrentMap<java.util.UUID,Long> epochs = new ConcurrentHashMap<>();
    final CountDownLatch exited = new CountDownLatch(2);
    final AtomicBoolean captureClosed = new AtomicBoolean();
    final CountDownLatch captureReleased = new CountDownLatch(1);
    volatile boolean stop;
    volatile boolean failed;
    volatile VoiceBackend backend;
    volatile MicrophoneCaptureService.FrameSource source;
    volatile Thread captureThread, recognitionThread, startupThread;
    Run(ConversationTarget target, AudioDevice device, VoiceSettings settings, boolean diagnostic) {
      this.target = target; this.device = device; this.settings = settings; this.diagnostic = diagnostic;
    }
  }

  public VoiceInputCoordinator(MicrophoneCaptureService capture, VoiceBackendFactory backends,
      Consumer<ConversationInput> submit, VoiceEventPublisher events, Clock clock) {
    this(capture,backends,submit,events,clock,target->true,System::nanoTime);
  }
  public VoiceInputCoordinator(MicrophoneCaptureService capture,VoiceBackendFactory backends,
      Consumer<ConversationInput> submit,VoiceEventPublisher events,Clock clock,
      java.util.function.Predicate<ConversationTarget> selected,java.util.function.LongSupplier ticks) {
    this(capture,backends,submit,events,clock,selected,ticks,new VoiceAudioGate(ticks));
  }
  public VoiceInputCoordinator(MicrophoneCaptureService capture,VoiceBackendFactory backends,
      Consumer<ConversationInput> submit,VoiceEventPublisher events,Clock clock,
      java.util.function.Predicate<ConversationTarget> selected,java.util.function.LongSupplier ticks,
      VoiceAudioGate audioGate) {
    this(capture,backends,submit,events,clock,selected,ticks,audioGate,VoiceAdvancedOptions::defaults);
  }
  public VoiceInputCoordinator(MicrophoneCaptureService capture,VoiceBackendFactory backends,
      Consumer<ConversationInput> submit,VoiceEventPublisher events,Clock clock,
      java.util.function.Predicate<ConversationTarget> selected,java.util.function.LongSupplier ticks,
      VoiceAudioGate audioGate,java.util.function.Supplier<VoiceAdvancedOptions> advanced) {
    this.advanced=Objects.requireNonNull(advanced);
    this.audioGate=Objects.requireNonNull(audioGate);
    this.selected=Objects.requireNonNull(selected);this.ticks=Objects.requireNonNull(ticks);
    this.capture = Objects.requireNonNull(capture); this.backends = Objects.requireNonNull(backends);
    this.submit = Objects.requireNonNull(submit); this.events = Objects.requireNonNull(events);
    this.clock = Objects.requireNonNull(clock);
  }
  private static Thread daemon(Runnable work, String name) {
    var thread = new Thread(work, name); thread.setDaemon(true); return thread;
  }
  public State state() { return state; }
  public State awaitStartup(java.time.Duration timeout) {
    long deadline=System.nanoTime()+timeout.toNanos();
    try {
      synchronized(guard) {
        while(state==State.STARTING) {
          long remaining=deadline-System.nanoTime();
          if(remaining<=0) break;
          TimeUnit.NANOSECONDS.timedWait(guard,remaining);
        }
        if(state!=State.STARTING)return state;
      }
    } catch(InterruptedException e) {
      off();Thread.currentThread().interrupt();throw new IllegalStateException("Voice initialization interrupted");
    }
    off();throw new IllegalStateException("Voice initialization timed out; waiting for native cleanup");
  }
  public int queuedSegments() { synchronized (guard) { return active == null ? 0 : active.segments.size(); } }
  private void change(State next) {
    state = next; guard.notifyAll(); events.publish(VoiceEventPublisher.Type.STATE_CHANGED, next.name());
  }
  public void start(ConversationTarget target, AudioDevice device, VoiceSettings settings) {
    start(target, device, settings, false);
  }
  public void startDiagnostic(ConversationTarget target, AudioDevice device, VoiceSettings settings) {
    start(target, device, settings, true);
  }
  private void start(ConversationTarget target, AudioDevice device, VoiceSettings settings, boolean diagnostic) {
    if (target == null || device == null || settings == null) throw new IllegalArgumentException("Select a microphone and target first");
    synchronized (guard) {
      if (closed || active != null) throw new IllegalStateException("Voice is already active or closed");
      var run = new Run(target, device, settings, diagnostic);
      active = run; change(State.STARTING);
      supervisor.execute(() -> supervise(run));
    }
  }
  public void off() {
    Run run;
    synchronized (guard) {
      run = active;
      if (run == null) { if (!closed) change(State.OFF); return; }
      run.stop = true; run.segments.clear(); run.epochs.clear(); change(State.STOPPING);
    }
    stopWorkers(run);
  }
  private void stopWorkers(Run run) {
    synchronized(guard) {if(run.startupThread!=null&&run.startupThread!=Thread.currentThread())run.startupThread.interrupt();}
    if (run.captureThread != null) run.captureThread.interrupt();
    if (run.recognitionThread != null) run.recognitionThread.interrupt();
    if (run.source != null && run.captureClosed.compareAndSet(false, true)) {
      // A broken audio driver must not hold the Shell caller. Only one Run can remain
      // active, and backend cleanup still waits for both workers and this real release.
      daemon(()->{
        try { run.source.close(); }
        catch (RuntimeException | LinkageError e) { run.failed = true; events.publish(VoiceEventPublisher.Type.RELEASE_FAILED, "capture"); }
        finally { run.captureReleased.countDown(); }
      },"voice-microphone-release").start();
    }
  }
  private void fail(Run run, VoiceEventPublisher.Type code) {stop(run,code,true);}
  private void stop(Run run,VoiceEventPublisher.Type code,boolean failed) {
    synchronized (guard) {
      if (run.stop) return;
      run.failed |= failed; run.stop = true; run.segments.clear(); run.epochs.clear(); change(State.STOPPING);
    }
    events.publish(code, "voice stopped");
    stopWorkers(run);
  }
  private boolean selected(Run run) {
    try{return selected.test(run.target);}catch(RuntimeException unavailable){return false;}
  }
  private boolean stale(Run run) {
    var stamp=run.lastFrame;if(stamp==null)return false;
    long wall=java.time.Duration.between(stamp.at(),clock.instant()).toMillis();
    return ticks.getAsLong()-stamp.nanos()>TimeUnit.SECONDS.toNanos(5)||wall>5000||wall < -5000;
  }
  private void supervise(Run run) {
    boolean captureStarted = false, recognitionStarted = false;
    try {
      if(!selected(run)){stop(run,VoiceEventPublisher.Type.TARGET_CHANGED,false);return;}
      synchronized(guard){if(run.stop)return;run.startupThread=Thread.currentThread();}
      try{run.backend = backends.open(run.settings);}
      finally{synchronized(guard){run.startupThread=null;}}
      if (run.stop) return;
      if(!selected(run)){stop(run,VoiceEventPublisher.Type.TARGET_CHANGED,false);return;}
      run.source = capture.open(run.device);
      if (run.source == null) throw new IllegalStateException("Capture source unavailable");
      synchronized (guard) {
        if (run.stop) return;
        run.captureThread = daemon(() -> captureLoop(run), "voice-capture");
        run.recognitionThread = daemon(() -> recognizeLoop(run), "voice-recognition");
        run.lastFrame = new FrameStamp(ticks.getAsLong(),clock.instant());
        run.captureThread.start(); captureStarted = true;
        run.recognitionThread.start(); recognitionStarted = true;
        change(State.LISTENING);
        if (run.diagnostic) run.deadline = timer.schedule(() -> stopDiagnostic(run), 20, TimeUnit.SECONDS);
      }
      while (!run.exited.await(250, TimeUnit.MILLISECONDS)) {
        if(!run.stop&&!selected(run)){stop(run,VoiceEventPublisher.Type.TARGET_CHANGED,false);continue;}
        if(!run.stop) {
          try{run.source.checkHealth();}catch(Exception healthFailure){fail(run,VoiceEventPublisher.Type.DEVICE_CHANGED);continue;}
          if(stale(run)) {
            var stamp=run.lastFrame;
            fail(run,ticks.getAsLong()-stamp.nanos()>TimeUnit.SECONDS.toNanos(5)
              ?VoiceEventPublisher.Type.CAPTURE_FAILED:VoiceEventPublisher.Type.CAPTURE_RESUMED);
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(run, VoiceEventPublisher.Type.BACKEND_FAILED);
    } catch (Exception | LinkageError e) {
      fail(run, run.backend == null ? VoiceEventPublisher.Type.BACKEND_FAILED : VoiceEventPublisher.Type.CAPTURE_FAILED);
    } finally {
      run.stop = true;
      if (run.deadline != null) run.deadline.cancel(false);
      if (!captureStarted) run.exited.countDown();
      if (!recognitionStarted) run.exited.countDown();
      stopWorkers(run);
      boolean interrupted = Thread.interrupted();
      while (run.exited.getCount() != 0) {
        try { run.exited.await(); } catch (InterruptedException e) { interrupted = true; }
      }
      if (run.source != null) {
        while (run.captureReleased.getCount() != 0) {
          try { run.captureReleased.await(); } catch (InterruptedException e) { interrupted = true; }
        }
      }
      run.segments.clear(); run.epochs.clear();
      if (run.backend != null) {
        try { run.backend.close(); } catch (RuntimeException | LinkageError e) {
          run.failed = true; events.publish(VoiceEventPublisher.Type.RELEASE_FAILED, "backend");
        }
      }
      synchronized (guard) {
        if (active == run) { active = null; change(closed ? State.CLOSED : run.failed ? State.FAILED : State.OFF); }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
  private void captureLoop(Run run) {
    var assembler = new SpeechSegmentAssembler(run.settings, clock);
    long previousEpoch = audioGate.epoch();
    try {
      while (!run.stop) {
        var frame = run.source.readFrame();
        if (run.stop) break;
        if (frame == null) { fail(run, VoiceEventPublisher.Type.CAPTURE_FAILED); break; }
        if(stale(run)){fail(run,VoiceEventPublisher.Type.CAPTURE_RESUMED);break;}
        VoicePcm.validateFrame(frame);
        run.lastFrame = new FrameStamp(ticks.getAsLong(),clock.instant());
        long epoch = audioGate.epoch();
        if (epoch != previousEpoch || audioGate.suppressed()) {
          assembler.reset(); run.segments.clear(); run.epochs.clear(); previousEpoch = epoch;
          if (audioGate.suppressed()) {
            run.backend.vad().probability(new float[VoiceSettings.WINDOW]);
            continue;
          }
        }
        var decision = assembler.accept(frame, run.backend.vad().probability(frame));
        switch (decision.reason()) {
          case COMPLETED -> {
            if (!run.stop && audioGate.allowed(epoch)) {
              var segment=decision.segment(); run.epochs.put(segment.id(),epoch);
              if (!run.segments.offer(segment)) {
                run.epochs.remove(segment.id());
                events.publish(VoiceEventPublisher.Type.SEGMENT_QUEUE_FULL, "segment dropped");
              }
            }
          }
          case SHORT_DROPPED -> events.publish(VoiceEventPublisher.Type.SHORT_DROPPED, "segment dropped");
          case MAX_DROPPED -> events.publish(VoiceEventPublisher.Type.MAX_DROPPED, "unfinished segment dropped");
          default -> { }
        }
      }
    } catch (InterruptedException e) {
      if (!run.stop) fail(run, VoiceEventPublisher.Type.CAPTURE_FAILED);
      Thread.currentThread().interrupt();
    } catch (Exception | LinkageError e) {
      fail(run, VoiceEventPublisher.Type.CAPTURE_FAILED);
    } finally { assembler.reset(); run.exited.countDown(); }
  }
  private void recognizeLoop(Run run) {
    var wakeGate=new VoiceWakeGate(clock);
    try {
      while (!run.stop) {
        var segment = run.segments.poll(200, TimeUnit.MILLISECONDS);
        if (segment == null) continue;
        var epoch = run.epochs.remove(segment.id());
        if (epoch == null || !audioGate.allowed(epoch)) continue;
        var result = SpeechResultFilter.filter(run.backend.recognizer().recognize(segment));
        if(!selected(run)){stop(run,VoiceEventPublisher.Type.TARGET_CHANGED,false);continue;}
        if(stale(run)){fail(run,VoiceEventPublisher.Type.CAPTURE_RESUMED);continue;}
        synchronized (guard) {
          if (run.stop || active != run || !audioGate.allowed(epoch)) continue;
          if (result.isEmpty()) { events.publish(VoiceEventPublisher.Type.RESULT_REJECTED, "recognition dropped"); continue; }
          if (run.diagnostic) { events.publish(VoiceEventPublisher.Type.DIAGNOSTIC_RESULT, result.get()); continue; }
          try {
            audioGate.ifAllowed(epoch, () -> {
              var acceptedText=wakeGate.filter(result.get(),run.target,advanced.get());
              if(acceptedText.isEmpty()) {
                events.publish(VoiceEventPublisher.Type.RESULT_REJECTED,"wake gate"); return;
              }
              submit.accept(new ConversationInput(segment.id(), InputSource.VOICE, run.target,
                  acceptedText.get(), segment.createdAt()));
            });
          } catch (RuntimeException e) {
            events.publish(VoiceEventPublisher.Type.INPUT_REJECTED, "input rejected");
          }
        }
      }
    } catch (InterruptedException e) {
      if (!run.stop) fail(run, VoiceEventPublisher.Type.RECOGNITION_FAILED);
      Thread.currentThread().interrupt();
    } catch (Exception | LinkageError e) {
      fail(run, VoiceEventPublisher.Type.RECOGNITION_FAILED);
    } finally { run.exited.countDown(); }
  }
  private void stopDiagnostic(Run run) {
    synchronized (guard) {
      if (active != run || run.stop) return;
      run.stop = true; run.segments.clear(); run.epochs.clear(); change(State.STOPPING);
    }
    stopWorkers(run);
  }
  public void close() {
    synchronized (guard) { if (closed) return; closed = true; }
    off(); timer.shutdownNow(); supervisor.shutdown();
    try { supervisor.awaitTermination(15, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    synchronized (guard) { if (active == null) change(State.CLOSED); }
  }
}