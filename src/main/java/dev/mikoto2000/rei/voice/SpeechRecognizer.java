package dev.mikoto2000.rei.voice;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 音声による対話に必要な発話Recognizerを扱う。 */
public interface SpeechRecognizer extends AutoCloseable {
  /** 音声による対話で用いるrecognizeを返す。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Recognition adapters can report checked native or worker transport failures.")
  String recognize(SpeechSegment segment) throws Exception;

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  void close();
}
