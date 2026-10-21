package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.input.InputSource;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 音声入力からの依頼かどうか処理の調整の結果と状態の契約を検証するテスト。 */
class VoiceInputCoordinatorTest {
  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);

  /** 検証に使う対象。 */
  final ConversationTarget target =
      new ConversationTarget(
          new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");

  /** 検証に使うdevice。 */
  final AudioDevice device = new AudioDevice("device", "test microphone", "test", "vendor", "1");

  /** 検証条件を再現するための取得元。 */
  static final class Source implements MicrophoneCaptureService.FrameSource {
    /** 検証に使う観測単位一覧。 */
    final BlockingQueue<float[]> frames = new LinkedBlockingQueue<>();

    /** 検証に使う読み取る。 */
    final AtomicInteger reads = new AtomicInteger();

    /** 検証に使う終了済み。 */
    volatile boolean closed;

    /** 検証用に、必要な内容を参照できるよう、音声フレームを読み取る。 */
    public float[] readFrame() throws InterruptedException {
      var frame = frames.take();
      reads.incrementAndGet();
      return frame.length == 0 ? null : frame;
    }

    /** 検証で利用した対象の終了を扱う。 */
    public void close() {
      closed = true;
      frames.offer(new float[0]);
    }

    /** {@code speech} の条件を再現するため、検証用の操作や状態を扱う。 */
    void speech(int utterances) {
      for (int j = 0; j < utterances; j++) {
        for (int i = 0; i < 16; i++) {
          var frame = new float[512];
          Arrays.fill(frame, .6f);
          frames.add(frame);
        }
        for (int i = 0; i < 60; i++) frames.add(new float[512]);
      }
    }
  }

  /** {@code backend} の条件を再現するための補助情報を返す。 */
  VoiceBackend backend(SpeechRecognizer recognizer, AtomicInteger vadClosed) {
    return new VoiceBackend(
        new VoiceActivityDetector() {
          /** {@code probability} の条件を再現するための補助情報を返す。 */
          public float probability(float[] frame) {
            return frame[0] > .2f ? 1 : 0;
          }

          /** 検証で利用した対象の終了を扱う。 */
          public void close() {
            vadClosed.incrementAndGet();
          }
        },
        recognizer);
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAlsoDiscardsCorrectionAlreadyCompletedButWaitingForPriorAsrInput()
      throws Exception {
    var source = new Source();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var firstEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var count = new AtomicInteger();
    var p = new VoiceCorrectionProperties();
    p.setEnabled(true);
    p.setMaxConcurrentRequests(2);
    try (var correction =
            new VoiceCorrectionService(
                p,
                i ->
                    new VoiceCorrectionContext.Snapshot(
                        target.project().id(), "test", List.of(), List.of()),
                (raw, context) -> {
                  if (raw.equals("交配の話")) {
                    firstEntered.countDown();
                    release.await();
                    return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
                  }
                  return VoiceCorrectionValidatorTest.corrected(raw, "機械学習の勾配");
                },
                new VoiceEventPublisher());
        var voice =
            new VoiceInputCoordinator(
                d -> source,
                s ->
                    backend(
                        new SpeechRecognizer() {
                          /** {@code recognize} の条件を再現するための補助情報を返す。 */
                          public String recognize(SpeechSegment segment) {
                            return count.incrementAndGet() == 1 ? "交配の話" : "機械学習の交配";
                          }

                          /** 検証で利用した対象の終了を扱う。 */
                          public void close() {}
                        },
                        new AtomicInteger()),
                submitted::add,
                new VoiceEventPublisher(),
                clock)) {
      voice.configureCorrection(correction, submitted::add);
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
      source.speech(1);
      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> correction.traces().stream().anyMatch(t -> t.original().equals("機械学習の交配")));
      assertThat(submitted).isEmpty();
      assertThat(voice.pendingCorrectionInputs()).hasSize(2);
      var second =
          voice.pendingCorrectionInputs().stream()
              .filter(i -> i.text().equals("機械学習の交配"))
              .findFirst()
              .orElseThrow();
      assertThat(voice.cancelPendingCorrection(second.inputId())).isTrue();
      release.countDown();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(submitted).hasSize(1));
      assertThat(submitted.getFirst().text()).isEqualTo("勾配の話");
      assertThat(voice.pendingCorrectionInputs()).isEmpty();
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void correctedTextIsSubmittedOnceWithOriginalVoiceIdentityAndDiagnosticNeverCallsLlm()
      throws Exception {
    var source = new Source();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var calls = new AtomicInteger();
    var selectedSource = new AtomicReference<>(source);
    var events = new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    var p = new VoiceCorrectionProperties();
    p.setEnabled(true);
    p.setMaxTotalTokens(0);
    try (var correction =
            new VoiceCorrectionService(
                p,
                i ->
                    new VoiceCorrectionContext.Snapshot(
                        target.project().id(), "test", List.of("微分"), List.of()),
                (raw, context) -> {
                  calls.incrementAndGet();
                  return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
                },
                new VoiceEventPublisher());
        var voice =
            new VoiceInputCoordinator(
                d -> selectedSource.get(),
                s ->
                    backend(
                        new SpeechRecognizer() {
                          /** {@code recognize} の条件を再現するための補助情報を返す。 */
                          public String recognize(SpeechSegment segment) {
                            return "レイ、交配の話";
                          }

                          /** 検証で利用した対象の終了を扱う。 */
                          public void close() {}
                        },
                        new AtomicInteger()),
                submitted::add,
                publisher,
                clock,
                t -> true,
                System::nanoTime,
                new VoiceAudioGate(System::nanoTime),
                () ->
                    new VoiceAdvancedOptions(
                        true,
                        "れい",
                        false,
                        false,
                        VoiceAdvancedOptions.defaults().ttsVoice(),
                        800))) {
      voice.configureCorrection(correction, submitted::add);
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(submitted).hasSize(1));
      assertThat(submitted.getFirst().text()).isEqualTo("勾配の話");
      assertThat(submitted.getFirst().source()).isEqualTo(InputSource.VOICE);
      assertThat(submitted.getFirst().target()).isEqualTo(target);
      voice.off();
      assertThat(correction.traces().getFirst().original()).isEqualTo("レイ、交配の話");
      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> voice.state() == VoiceInputCoordinator.State.OFF);
      var diagnosticSource = new Source();
      selectedSource.set(diagnosticSource);
      voice.startDiagnostic(target, device, VoiceSettings.defaults());
      diagnosticSource.speech(1);
      await()
          .atMost(Duration.ofSeconds(3))
          .until(
              () ->
                  events.stream()
                      .anyMatch(e -> e.type() == VoiceEventPublisher.Type.DIAGNOSTIC_RESULT));
      assertThat(calls).hasValue(1);
      assertThat(submitted).hasSize(1);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void voiceOffDuringCorrectionDiscardsLateResultWithoutBlockingAsrCleanup() throws Exception {
    var source = new Source();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var p = new VoiceCorrectionProperties();
    p.setEnabled(true);
    try (var correction =
            new VoiceCorrectionService(
                p,
                i ->
                    new VoiceCorrectionContext.Snapshot(
                        target.project().id(), "test", List.of(), List.of()),
                (raw, context) -> {
                  entered.countDown();
                  release.await();
                  return VoiceCorrectionValidatorTest.corrected(raw, "勾配の話");
                },
                new VoiceEventPublisher());
        var voice =
            new VoiceInputCoordinator(
                d -> source,
                s ->
                    backend(
                        new SpeechRecognizer() {
                          /** {@code recognize} の条件を再現するための補助情報を返す。 */
                          public String recognize(SpeechSegment segment) {
                            return "交配の話";
                          }

                          /** 検証で利用した対象の終了を扱う。 */
                          public void close() {}
                        },
                        new AtomicInteger()),
                submitted::add,
                new VoiceEventPublisher(),
                clock)) {
      voice.configureCorrection(correction, submitted::add);
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      voice.off();
      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> voice.state() == VoiceInputCoordinator.State.OFF);
      release.countDown();
      assertThat(submitted).isEmpty();
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void enabledWakePrefixIsRemovedButVoiceOwnershipAndLiteralTextRemain() {
    var source = new Source();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var count = new AtomicInteger();
    var options =
        new VoiceAdvancedOptions(
            true, "れい", false, false, VoiceAdvancedOptions.defaults().ttsVoice(), 800);
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return count.incrementAndGet() == 1 ? "れいめいを確認" : "レイ、Ａ.txt を確認";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    new AtomicInteger()),
            submitted::add,
            new VoiceEventPublisher(),
            clock,
            t -> true,
            System::nanoTime,
            new VoiceAudioGate(System::nanoTime),
            () -> options)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(2);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(submitted).hasSize(1));
      assertThat(submitted.getFirst().text()).isEqualTo("Ａ.txt を確認");
      assertThat(submitted.getFirst().source()).isEqualTo(InputSource.VOICE);
      assertThat(submitted.getFirst().target()).isEqualTo(target);
    }
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void playbackDiscardsRecognitionAlreadyInFlightAndSuppressesCapturedEcho() throws Exception {
    var source = new Source();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var recognized = new AtomicInteger();
    var tick = new AtomicLong();
    var gate = new VoiceAudioGate(tick::get);
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) throws Exception {
                        recognized.incrementAndGet();
                        entered.countDown();
                        release.await();
                        return "echo";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    new AtomicInteger()),
            submitted::add,
            new VoiceEventPublisher(),
            clock,
            t -> true,
            System::nanoTime,
            gate)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      try (var playback = gate.playback(Duration.ofMillis(800))) {
        source.speech(2);
        release.countDown();
        await().atMost(Duration.ofSeconds(3)).until(() -> source.reads.get() >= 228);
      }
      source.speech(1);
      await().atMost(Duration.ofSeconds(3)).until(() -> source.reads.get() >= 304);
      assertThat(submitted).isEmpty();
      assertThat(recognized).hasValue(1);
      tick.set(800_000_000L);
      source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(submitted).hasSize(1));
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void stopInterruptsSlowNativeStartupWithoutOpeningMicrophone() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var captures = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> {
              captures.incrementAndGet();
              return new Source();
            },
            s -> {
              entered.countDown();
              try {
                Thread.sleep(60000);
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
              }
              throw new AssertionError("startup should have been interrupted");
            },
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      voice.off();
      assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(captures).hasValue(0);
    }
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void oldStartupHandleCannotWaitOnOrCancelAReplacementRun() throws Exception {
    var entered = new CountDownLatch(1);
    var opens = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> new Source(),
            settings -> {
              if (opens.incrementAndGet() == 2) {
                entered.countDown();
                Thread.sleep(60000);
              }
              return backend(
                  new SpeechRecognizer() {
                    /** {@code recognize} の条件を再現するための補助情報を返す。 */
                    public String recognize(SpeechSegment segment) {
                      return "unused";
                    }

                    /** 検証で利用した対象の終了を扱う。 */
                    public void close() {}
                  },
                  new AtomicInteger());
            },
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      var old = voice.start(target, device, VoiceSettings.defaults());
      assertThat(old.await(Duration.ofSeconds(3))).isEqualTo(VoiceInputCoordinator.State.LISTENING);
      voice.off();
      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> voice.state() == VoiceInputCoordinator.State.OFF);
      voice.start(target, device, VoiceSettings.defaults());
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      Thread.currentThread().interrupt();
      try {
        assertThat(old.await(Duration.ZERO)).isEqualTo(VoiceInputCoordinator.State.OFF);
        assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STARTING);
      } finally {
        Thread.interrupted();
      }
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void defaultIsOffAndUnselectedMicDoesNotOpenBackend() {
    var calls = new AtomicInteger();
    var source = new Source();
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s -> {
              calls.incrementAndGet();
              throw new IllegalStateException();
            },
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThatThrownBy(() -> voice.start(target, null, VoiceSettings.defaults()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(calls.get()).isZero();
    }
  }

  /** 音声による対話で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void silenceAutomaticallySubmitsOneVoiceInputToCapturedTarget() {
    var source = new Source();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var closed = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "こんにちは";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    closed),
            submitted::add,
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(submitted).hasSize(1));
      assertThat(submitted.getFirst().source()).isEqualTo(InputSource.VOICE);
      assertThat(submitted.getFirst().target()).isEqualTo(target);
      assertThat(submitted.getFirst().text()).isEqualTo("こんにちは");
      voice.off();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(closed.get()).isEqualTo(1);
      assertThat(source.closed).isTrue();
    }
  }

  /** 音声による対話で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void slowAsrDoesNotBlockCaptureAndSegmentQueueStaysBounded() throws Exception {
    var source = new Source();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var events = new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) throws Exception {
                        entered.countDown();
                        release.await();
                        return "hello";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    new AtomicInteger()),
            input -> {},
            publisher,
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      source.speech(5);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(source.reads.get()).isGreaterThanOrEqualTo(324));
      assertThat(voice.queuedSegments()).isEqualTo(2);
      assertThat(events)
          .anyMatch(event -> event.type() == VoiceEventPublisher.Type.SEGMENT_QUEUE_FULL);
      voice.off();
      release.countDown();
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void offDoesNotReleaseNativeResourcesDuringDecodeOrSubmitLateResult() throws Exception {
    var source = new Source();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) throws Exception {
                        entered.countDown();
                        boolean done = false;
                        while (!done)
                          try {
                            release.await();
                            done = true;
                          } catch (InterruptedException ignored) {
                          }
                        return "late";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {
                        closed.incrementAndGet();
                      }
                    },
                    new AtomicInteger()),
            submitted::add,
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      voice.off();
      assertThat(closed.get()).isZero();
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING);
      release.countDown();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(closed.get()).isEqualTo(1);
      assertThat(submitted).isEmpty();
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void nativeInitializationFailureLeavesCliAliveAndMicUnopened() {
    var opens = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> {
              opens.incrementAndGet();
              return new Source();
            },
            s -> {
              throw new UnsatisfiedLinkError("JNI unavailable");
            },
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(opens.get()).isZero();
      voice.off();
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void captureDisconnectStopsWithoutSwitchingDevice() {
    var source = new Source();
    var opens = new AtomicInteger();
    var released = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> {
              assertThat(d).isEqualTo(device);
              opens.incrementAndGet();
              return source;
            },
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "unused";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    released),
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.frames.add(new float[0]);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(opens.get()).isEqualTo(1);
      assertThat(released.get()).isEqualTo(1);
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectedRecognitionCannotExecuteSlashCommands() {
    var source = new Source();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var events = new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return " /voice on";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    new AtomicInteger()),
            submitted::add,
            publisher,
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () ->
                  assertThat(events)
                      .anyMatch(e -> e.type() == VoiceEventPublisher.Type.RESULT_REJECTED));
      assertThat(submitted).isEmpty();
    }
  }

  /** 発話区間の検出失敗情報について、音声を停止することを確認する。 */
  @Test
  void vadFailureStopsVoiceAndReleasesBothResources() {
    var source = new Source();
    var vadClosed = new AtomicInteger();
    var asrClosed = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                new VoiceBackend(
                    new VoiceActivityDetector() {
                      /** {@code probability} の条件を再現するための補助情報を返す。 */
                      public float probability(float[] frame) {
                        throw new IllegalStateException("VAD failed");
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {
                        vadClosed.incrementAndGet();
                      }
                    },
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "unused";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {
                        asrClosed.incrementAndGet();
                      }
                    }),
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(vadClosed.get()).isEqualTo(1);
      assertThat(asrClosed.get()).isEqualTo(1);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void diagnosticDisplaysRecognitionWithoutSendingToAgent() {
    var source = new Source();
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var events = new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "診断の発話";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    new AtomicInteger()),
            submitted::add,
            publisher,
            clock)) {
      voice.startDiagnostic(target, device, VoiceSettings.defaults());
      source.speech(1);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () ->
                  assertThat(events)
                      .anyMatch(
                          e ->
                              e.type() == VoiceEventPublisher.Type.DIAGNOSTIC_RESULT
                                  && e.detail().equals("診断の発話")));
      assertThat(submitted).isEmpty();
      voice.off();
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void offDuringNativeInitializationClosesEventuallyWithoutOpeningMic() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var opened = new AtomicInteger();
    var closed = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> {
              opened.incrementAndGet();
              return new Source();
            },
            s -> {
              entered.countDown();
              boolean interrupted = false;
              // Model a native call that cannot react to Java interruption until it returns.
              while (release.getCount() != 0) {
                try {
                  release.await();
                } catch (InterruptedException e) {
                  interrupted = true;
                }
              }
              if (interrupted) Thread.currentThread().interrupt();
              return backend(
                  new SpeechRecognizer() {
                    /** {@code recognize} の条件を再現するための補助情報を返す。 */
                    public String recognize(SpeechSegment segment) {
                      return "unused";
                    }

                    /** 検証で利用した対象の終了を扱う。 */
                    public void close() {}
                  },
                  closed);
            },
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      voice.off();
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING);
      release.countDown();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(opened.get()).isZero();
      assertThat(closed.get()).isEqualTo(1);
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void stalledCaptureIsStoppedByWatchdogWithoutFallback() {
    var source = new Source();
    var closed = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "unused";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    closed),
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      await()
          .atMost(Duration.ofSeconds(8))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(source.closed).isTrue();
      assertThat(closed.get()).isEqualTo(1);
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void captureCloseFailureIsReportedAsFailedInsteadOfOff() {
    var released = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var source =
        new MicrophoneCaptureService.FrameSource() {
          /** 検証用に、必要な内容を参照できるよう、音声フレームを読み取る。 */
          public float[] readFrame() throws Exception {
            released.await();
            return null;
          }

          /** 検証で利用した対象の終了を扱う。 */
          public void close() {
            released.countDown();
            throw new IllegalStateException("close failed");
          }
        };
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "unused";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    closed),
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      assertThat(voice.awaitStartup(Duration.ofSeconds(3)))
          .isEqualTo(VoiceInputCoordinator.State.LISTENING);
      voice.off();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(closed.get()).isEqualTo(1);
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {127, 1024, -1})
  void invalidCaptureFrameNeverReachesNativeVad(int kind) {
    var source = new Source();
    var nativeCalls = new AtomicInteger();
    float[] frame = new float[kind < 0 ? 512 : kind];
    if (kind < 0) frame[0] = Float.NaN;
    source.frames.add(frame);
    try (var voice =
        new VoiceInputCoordinator(
            d -> source,
            s ->
                new VoiceBackend(
                    new VoiceActivityDetector() {
                      /** {@code probability} の条件を再現するための補助情報を返す。 */
                      public float probability(float[] value) {
                        nativeCalls.incrementAndGet();
                        return 0;
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    },
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "unused";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    }),
            input -> {},
            new VoiceEventPublisher(),
            clock)) {
      voice.start(target, device, VoiceSettings.defaults());
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(nativeCalls.get()).isZero();
    }
  }
}
