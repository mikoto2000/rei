package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 音声稼働状態受付から終了までの管理の結果と状態の契約を検証するテスト。 */
class VoiceHealthLifecycleTest {
  /** 検証に使う対象。 */
  final ConversationTarget target =
      new ConversationTarget(
          new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");

  /** 検証に使うdevice。 */
  final AudioDevice device = new AudioDevice("device", "test", "description", "vendor", "1");

  /** 検証条件を再現するための変更可能な時刻の取得元。 */
  static final class MutableClock extends Clock {
    /** 検証に使う時刻。 */
    final AtomicReference<Instant> time =
        new AtomicReference<>(Instant.parse("2026-10-09T08:00:00Z"));

    /** 検証用に、音声による対話で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId zone) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return time.get();
    }
  }

  /** {@code backend} の条件を再現するための補助情報を返す。 */
  VoiceBackend backend(SpeechRecognizer recognizer) {
    return new VoiceBackend(
        new VoiceActivityDetector() {
          /** {@code probability} の条件を再現するための補助情報を返す。 */
          public float probability(float[] frame) {
            return frame[0] > .2f ? 1 : 0;
          }

          /** 検証で利用した対象の終了を扱う。 */
          public void close() {}
        },
        recognizer);
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void changedTargetStopsWithoutSendingLateDecodedText() throws Exception {
    var selected = new AtomicBoolean(true);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var submitted = new CopyOnWriteArrayList<ConversationInput>();
    var source = new VoiceInputCoordinatorTest.Source();
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
                      public void close() {}
                    }),
            submitted::add,
            publisher,
            new MutableClock(),
            t -> selected.get(),
            System::nanoTime)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      selected.set(false);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING));
      release.countDown();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      assertThat(submitted).isEmpty();
      assertThat(events).anyMatch(e -> e.type() == VoiceEventPublisher.Type.TARGET_CHANGED);
      assertThat(source.closed).isTrue();
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resumeGapStopsEvenWhenAsrFinishesBeforeNextCaptureFrame() throws Exception {
    var clock = new MutableClock();
    var ticks = new AtomicLong();
    var source = new VoiceInputCoordinatorTest.Source();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
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
                      public String recognize(SpeechSegment segment) throws Exception {
                        entered.countDown();
                        release.await();
                        return "stale";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    }),
            submitted::add,
            publisher,
            clock,
            t -> true,
            ticks::get)) {
      voice.start(target, device, VoiceSettings.defaults());
      source.speech(1);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      clock.time.updateAndGet(t -> t.plusSeconds(10));
      release.countDown();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(submitted).isEmpty();
      assertThat(events).anyMatch(e -> e.type() == VoiceEventPublisher.Type.CAPTURE_RESUMED);
      assertThat(source.closed).isTrue();
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void deviceHealthFailureStopsEvenIfDriverKeepsReturningSilentFrames() {
    var healthy = new AtomicBoolean(true);
    var closed = new AtomicBoolean();
    var reads = new AtomicInteger();
    var publisher = new VoiceEventPublisher();
    var events = new CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    publisher.subscribe(events::add);
    var source =
        new MicrophoneCaptureService.FrameSource() {
          /** 検証用に、必要な内容を参照できるよう、音声フレームを読み取る。 */
          public float[] readFrame() throws Exception {
            Thread.sleep(5);
            reads.incrementAndGet();
            return new float[512];
          }

          /** 検証用に、後続の操作を進められるか判断するため、稼働状態を確認する。 */
          public void checkHealth() {
            if (!healthy.get()) throw new IllegalStateException("endpoint gone");
          }

          /** 検証で利用した対象の終了を扱う。 */
          public void close() {
            closed.set(true);
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
                        return "";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {}
                    }),
            i -> {},
            publisher,
            new MutableClock())) {
      voice.start(target, device, VoiceSettings.defaults());
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(reads.get()).isGreaterThan(2));
      healthy.set(false);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(
              () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.FAILED));
      assertThat(closed).isTrue();
      assertThat(events).anyMatch(e -> e.type() == VoiceEventPublisher.Type.DEVICE_CHANGED);
    }
  }

  /** 繰り返されたについて、停止解放する各対象バックエンドを開始することを確認する。 */
  @Test
  void repeatedStartStopReleasesEachBackendAndCaptureExactlyOnce() {
    var sources = new CopyOnWriteArrayList<VoiceInputCoordinatorTest.Source>();
    var nativeClosed = new AtomicInteger();
    try (var voice =
        new VoiceInputCoordinator(
            d -> {
              var source = new VoiceInputCoordinatorTest.Source();
              sources.add(source);
              return source;
            },
            s ->
                backend(
                    new SpeechRecognizer() {
                      /** {@code recognize} の条件を再現するための補助情報を返す。 */
                      public String recognize(SpeechSegment segment) {
                        return "";
                      }

                      /** 検証で利用した対象の終了を扱う。 */
                      public void close() {
                        nativeClosed.incrementAndGet();
                      }
                    }),
            i -> {},
            new VoiceEventPublisher(),
            new MutableClock())) {
      for (int cycle = 0; cycle < 12; cycle++) {
        voice.start(target, device, VoiceSettings.defaults());
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(
                () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.LISTENING));
        voice.off();
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(
                () -> assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF));
      }
      assertThat(nativeClosed).hasValue(12);
      assertThat(sources).hasSize(12).allMatch(source -> source.closed);
    }
  }
}
