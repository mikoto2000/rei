package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Guardedマイク取得記録の結果と状態の契約を検証するテスト。 */
class GuardedMicrophoneCaptureTest {
  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void missingEndpointNeverOpensJavaSoundAndDisconnectedSourceFailsHealth() throws Exception {
    var device = new AudioDevice("dry", "DRY (VT-4)", "input", "vendor", "1");
    var endpoints = new AtomicReference<List<WindowsAudioEndpoints.Endpoint>>(List.of());
    var monitor = new WindowsMicrophoneMonitor(endpoints::get);
    var opens = new AtomicInteger();
    var closes = new AtomicInteger();
    MicrophoneCaptureService delegate =
        d -> {
          opens.incrementAndGet();
          return new MicrophoneCaptureService.FrameSource() {
            /** 検証用に、必要な内容を参照できるよう、音声フレームを読み取る。 */
            public float[] readFrame() {
              return new float[512];
            }

            /** 検証で利用した対象の終了を扱う。 */
            public void close() {
              closes.incrementAndGet();
            }
          };
        };
    var capture = new GuardedMicrophoneCapture(delegate, monitor);
    assertThatThrownBy(() -> capture.open(device)).isInstanceOf(IllegalStateException.class);
    assertThat(opens.get()).isZero();
    endpoints.set(List.of(new WindowsAudioEndpoints.Endpoint("endpoint", device.name(), 1)));
    var source = capture.open(device);
    source.checkHealth();
    assertThat(source.readFrame()).hasSize(512);
    endpoints.set(List.of(new WindowsAudioEndpoints.Endpoint("endpoint", device.name(), 8)));
    assertThatThrownBy(source::checkHealth).isInstanceOf(IllegalStateException.class);
    source.close();
    assertThat(closes.get()).isEqualTo(1);
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void endpointChangeWhileJavaSoundOpensClosesTheJustOpenedSource() {
    var device = new AudioDevice("dry", "DRY (VT-4)", "input", "vendor", "1");
    var endpoints =
        new AtomicReference<>(
            List.of(new WindowsAudioEndpoints.Endpoint("endpoint", device.name(), 1)));
    var monitor = new WindowsMicrophoneMonitor(endpoints::get);
    var closes = new AtomicInteger();
    MicrophoneCaptureService delegate =
        d -> {
          endpoints.set(List.of());
          return new MicrophoneCaptureService.FrameSource() {
            /** 検証用に、必要な内容を参照できるよう、音声フレームを読み取る。 */
            public float[] readFrame() {
              return null;
            }

            /** 検証で利用した対象の終了を扱う。 */
            public void close() {
              closes.incrementAndGet();
            }
          };
        };
    assertThatThrownBy(() -> new GuardedMicrophoneCapture(delegate, monitor).open(device))
        .isInstanceOf(IllegalStateException.class);
    assertThat(closes.get()).isEqualTo(1);
  }
}
