package dev.mikoto2000.rei.voice;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.AudioSystem;
import dev.mikoto2000.rei.application.input.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectContext;

/**
 * 検証用音声を実時間で再生し、隔離した認識処理から会話受付までの遅延を測定する。
 *
 * <p>Opt-in real-time file capture replay through actual isolated workers/coordinator/Gateway/Run
 * dispatch. Runner is a local measuring callback: no LLM, Tool or external Agent execution.
 */
public final class VoiceLatencyReplay {
  /** 検証用音声を実時間で再生し、隔離した認識処理から会話受付までの遅延を測定する。 */
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("BUNDLE FIXTURES OUTPUT");
    Path bundle = Path.of(args[0]), fixtures = Path.of(args[1]), output = Path.of(args[2]);
    VoiceModelManifest.pinned().verify(bundle);
    var clock = Clock.systemUTC();
    var lifecycle =
        new SessionLifecycle(
            new FileSessionRepository(output.resolveSibling("replay-sessions.json")), clock);
    var project =
        new ProjectContext(
            UUID.randomUUID().toString(), "voice-latency-replay", output.getParent());
    var target =
        new ConversationTarget(
            project, lifecycle.create(project, "offline latency evaluation").sessionId());
    // 音声入力の来歴が共通の会話受付まで保たれることを確認し、実行回数を計測する。
    var runs = new AtomicInteger();
    var router =
        new ConversationInputRouter(
            Runnable::run,
            (context, text, queue) -> {
              if (!context.voiceInput()) throw new AssertionError("Voice provenance lost");
              runs.incrementAndGet();
            });
    var gateway = new ConversationInputGateway(lifecycle, router::submit, router, clock);
    try (var writer = Files.newBufferedWriter(output);
        var factory = new IsolatedVoiceBackendFactory(() -> bundle)) {
      writer.write("file\tendToGatewayMs\ttext64\tvoiceRuns\tplaybackFinished\n");
      for (String line : Files.readAllLines(fixtures.resolve("fixtures.tsv"))) {
        if (line.isBlank()) continue;
        String file = line.split("\t", 2)[0];
        if (!file.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Unsafe fixture");
        float[] samples;
        try (var source = AudioSystem.getAudioInputStream(fixtures.resolve(file).toFile());
            var pcm = AudioFormatConverter.toPcm16(source)) {
          samples = VoicePcm.decode(pcm.readAllBytes());
        }
        var lastSpeech = new AtomicLong();
        var inputEnd = new AtomicLong();
        var delivered = new CountDownLatch(1);
        var faults = new CopyOnWriteArrayList<String>();
        MicrophoneCaptureService capture =
            device ->
                new MicrophoneCaptureService.FrameSource() {
                  /** 検証に使う開始位置。 */
                  int offset;

                  /** 検証に使う次。 */
                  long next;

                  /** 検証に使う終了済み。 */
                  volatile boolean closed;

                  /** 検証条件に従って音声を供給し、実際の入力に近い取得経路を再現する。 */
                  public float[] readFrame() throws Exception {
                    if (next == 0) next = System.nanoTime();
                    else next += 32_000_000L;
                    while (!closed && System.nanoTime() < next) {
                      LockSupport.parkNanos(next - System.nanoTime());
                      if (Thread.interrupted()) throw new InterruptedException();
                    }
                    if (closed) throw new InterruptedException();
                    float[] frame = new float[512];
                    int size = Math.min(512, Math.max(0, samples.length - offset));
                    if (size > 0) System.arraycopy(samples, offset, frame, 0, size);
                    offset += 512;
                    if (offset >= samples.length) inputEnd.compareAndSet(0, System.nanoTime());
                    return frame;
                  }

                  /** 試作で使用した音声入力元や認識処理を終了する。 */
                  public void close() {
                    closed = true;
                  }
                };
        VoiceBackendFactory measured =
            settings -> {
              var actual = factory.open(settings);
              return new VoiceBackend(
                  new VoiceActivityDetector() {
                    /** 発話と判定する確率を取り出し、発話検出の状態を確認できるようにする。 */
                    public float probability(float[] frame) throws Exception {
                      float value = actual.vad().probability(frame);
                      if (value >= settings.threshold()) lastSpeech.set(System.nanoTime());
                      return value;
                    }

                    /** 試作で使用した音声入力元や認識処理を終了する。 */
                    public void close() {
                      actual.vad().close();
                    }
                  },
                  actual.recognizer());
            };
        var events = new VoiceEventPublisher();
        events.subscribe(
            event -> {
              if (event.type().name().endsWith("FAILED")
                  || event.type() == VoiceEventPublisher.Type.SEGMENT_QUEUE_FULL)
                faults.add(event.type().name());
            });
        try (var voice =
            new VoiceInputCoordinator(
                capture,
                measured,
                input -> {
                  gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE);
                  long elapsed = System.nanoTime() - lastSpeech.get();
                  synchronized (writer) {
                    try {
                      writer.write(
                          file
                              + "\t"
                              + elapsed / 1e6
                              + "\t"
                              + Base64.getEncoder()
                                  .encodeToString(
                                      input
                                          .text()
                                          .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                              + "\t"
                              + runs.get()
                              + "\t"
                              + (inputEnd.get() != 0)
                              + "\n");
                      writer.flush();
                    } catch (java.io.IOException e) {
                      throw new java.io.UncheckedIOException(e);
                    }
                  }
                  System.out.println("GATEWAY " + file + " latencyMs=" + elapsed / 1e6);
                  if (inputEnd.get() != 0) delivered.countDown();
                },
                events,
                clock)) {
          voice.start(
              target,
              new AudioDevice("file-replay", "offline file replay", "test", "test", "1"),
              VoiceSettings.defaults());
          if (voice.awaitStartup(Duration.ofMinutes(4)) != VoiceInputCoordinator.State.LISTENING)
            throw new AssertionError("Replay did not start");
          if (!delivered.await(3, TimeUnit.MINUTES)) throw new AssertionError("No Gateway input");
          voice.off();
          long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
          while (voice.state() == VoiceInputCoordinator.State.STOPPING
              && System.nanoTime() < deadline) Thread.sleep(20);
          if (voice.state() != VoiceInputCoordinator.State.OFF
              || factory.liveWorkers() != 0
              || !faults.isEmpty()) throw new AssertionError("Replay cleanup failed " + faults);
        }
      }
    }
    System.out.println(
        "REPLAY PASSED: actual Gateway, VOICE Run dispatch, workers exited; no microphone/LLM");
  }
}
