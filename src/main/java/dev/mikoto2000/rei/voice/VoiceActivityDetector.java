package dev.mikoto2000.rei.voice;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 音声による対話に必要な音声作業状況検出の窓口を扱う。 */
public interface VoiceActivityDetector extends AutoCloseable {
  /** 音声による対話で用いるprobabilityを返す。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Detection adapters can report checked native or worker transport failures.")
  float probability(float[] frame) throws Exception;

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  void close();
}
