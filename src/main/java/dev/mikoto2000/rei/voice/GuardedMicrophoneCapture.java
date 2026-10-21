package dev.mikoto2000.rei.voice;

import java.util.Objects;

/**
 * 音声による対話に必要なGuardedマイク取得記録を扱う。
 *
 * <p>Checks the selected endpoint before and after opening, and during capture independently of
 * frames.
 */
public final class GuardedMicrophoneCapture implements MicrophoneCaptureService {
  /** 取得記録。 */
  private final MicrophoneCaptureService capture;

  /** 排他制御。 */
  private final WindowsMicrophoneMonitor monitor;

  /** 指定された値や利用先を使って、GuardedMicrophoneCaptureを初期化する。 */
  public GuardedMicrophoneCapture(
      MicrophoneCaptureService capture, WindowsMicrophoneMonitor monitor) {
    this.capture = Objects.requireNonNull(capture);
    this.monitor = Objects.requireNonNull(monitor);
  }

  /** 必要な対象を利用できるよう、Guardedマイク取得記録の利用を開始する。 */
  public FrameSource open(AudioDevice device) throws Exception {
    var binding = monitor.bind(device);
    var source = capture.open(device);
    try {
      monitor.check(binding);
    } catch (RuntimeException failure) {
      source.close();
      throw failure;
    }
    return new FrameSource() {
      /** 必要な内容を参照できるよう、観測単位を読み取る。 */
      public float[] readFrame() throws Exception {
        return source.readFrame();
      }

      /** 後続の操作を進められるか判断するため、稼働状態を確認する。 */
      public void checkHealth() throws Exception {
        monitor.check(binding);
        source.checkHealth();
      }

      /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
      public void close() {
        source.close();
      }
    };
  }
}
