package dev.mikoto2000.rei.voice;

import java.io.IOException;
import javax.sound.sampled.LineUnavailableException;

/** マイク取得記録のユースケースを実現する。 */
@FunctionalInterface
public interface MicrophoneCaptureService {
  /** 必要な対象を利用できるよう、マイク取得記録サービスの利用を開始する。 */
  FrameSource open(AudioDevice device) throws LineUnavailableException, InterruptedException;

  /** 音声による対話に必要な観測単位取得元を扱う。 */
  interface FrameSource extends AutoCloseable {
    /** 必要な内容を参照できるよう、観測単位を読み取る。ストリームの終了時は空の配列を返す。 */
    float[] readFrame() throws IOException, InterruptedException;

    /** 後続の操作を進められるか判断するため、稼働状態を確認する。 */
    default void checkHealth() throws IOException {}

    /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
    void close();
  }
}
