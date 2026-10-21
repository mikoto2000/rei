package dev.mikoto2000.rei.voice;

/** 音声による対話に必要な発話Recognizerを扱う。 */
public interface SpeechRecognizer extends AutoCloseable {
  /** 音声による対話で用いるrecognizeを返す。 */
  String recognize(SpeechSegment segment) throws Exception;

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  void close();
}
