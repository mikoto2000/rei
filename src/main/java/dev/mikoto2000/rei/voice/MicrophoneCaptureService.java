package dev.mikoto2000.rei.voice;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** マイク取得記録のユースケースを実現する。 */
@FunctionalInterface
public interface MicrophoneCaptureService {
  /** 必要な対象を利用できるよう、マイク取得記録サービスの利用を開始する。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Device adapters can report different checked acquisition failures during startup.")
  FrameSource open(AudioDevice device) throws Exception;

  /** 音声による対話に必要な観測単位取得元を扱う。 */
  interface FrameSource extends AutoCloseable {
    /** 必要な内容を参照できるよう、観測単位を読み取る。ストリームの終了時は空の配列を返す。 */
    @SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        matchType = SuppressMatchType.EXACT,
        justification = "A frame source can report checked read and device failures from its underlying backend.")
    float[] readFrame() throws Exception;

    /** 後続の操作を進められるか判断するため、稼働状態を確認する。 */
    @SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        matchType = SuppressMatchType.EXACT,
        justification = "A frame source can report the checked health failure of its underlying device.")
    default void checkHealth() throws Exception {}

    /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
    void close();
  }
}
