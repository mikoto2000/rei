package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 音声Slowマイク終了の結果と状態の契約を検証するテスト。 */
@Timeout(10)
class VoiceSlowMicrophoneCloseTest {
  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void offReturnsWhileDriverCloseIsBlockedButBackendIsHeldUntilCaptureRelease() throws Exception {
    var closeEntered = new CountDownLatch(1);
    var releaseClose = new CountDownLatch(1);
    var backendClosed = new AtomicBoolean();
    var closeCalls = new AtomicInteger();
    MicrophoneCaptureService capture =
        d ->
            new MicrophoneCaptureService.FrameSource() {
              /** 検証用に、必要な内容を参照できるよう、音声フレームを読み取る。 */
              public float[] readFrame() throws Exception {
                Thread.sleep(10);
                return new float[512];
              }

              /** 検証で利用した対象の終了を扱う。 */
              public void close() {
                closeCalls.incrementAndGet();
                closeEntered.countDown();
                boolean interrupted = false;
                while (releaseClose.getCount() > 0)
                  try {
                    releaseClose.await();
                  } catch (InterruptedException stop) {
                    interrupted = true;
                  }
                if (interrupted) Thread.currentThread().interrupt();
              }
            };
    VoiceBackendFactory backend =
        s ->
            new VoiceBackend(
                new VoiceActivityDetector() {
                  /** {@code probability} の条件を再現するための補助情報を返す。 */
                  public float probability(float[] frame) {
                    return 0;
                  }

                  /** 検証で利用した対象の終了を扱う。 */
                  public void close() {
                    backendClosed.set(true);
                  }
                },
                new SpeechRecognizer() {
                  /** {@code recognize} の条件を再現するための補助情報を返す。 */
                  public String recognize(SpeechSegment segment) {
                    return "unused";
                  }

                  /** 検証で利用した対象の終了を扱う。 */
                  public void close() {}
                });
    var voice =
        new VoiceInputCoordinator(
            capture,
            backend,
            i -> {
              throw new AssertionError("Unexpected submission");
            },
            new VoiceEventPublisher(),
            Clock.systemUTC());
    var target =
        new ConversationTarget(
            new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");
    var device = new AudioDevice("dry", "DRY (VT-4)", "input", "vendor", "1");
    var caller =
        Executors.newSingleThreadExecutor(
            r -> {
              var thread = new Thread(r, "off-fixture");
              thread.setDaemon(true);
              return thread;
            });
    try {
      voice.start(target, device, VoiceSettings.defaults());
      assertThat(voice.awaitStartup(Duration.ofSeconds(2)))
          .isEqualTo(VoiceInputCoordinator.State.LISTENING);
      var stopped = caller.submit(voice::off);
      stopped.get(300, TimeUnit.MILLISECONDS);
      assertThat(closeEntered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.STOPPING);
      assertThat(backendClosed.get()).isFalse();
      assertThatThrownBy(() -> voice.start(target, device, VoiceSettings.defaults()))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      releaseClose.countDown();
      voice.close();
      caller.shutdownNow();
    }
    assertThat(closeCalls.get()).isEqualTo(1);
    assertThat(backendClosed.get()).isTrue();
  }
}
