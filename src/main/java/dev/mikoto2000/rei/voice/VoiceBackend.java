package dev.mikoto2000.rei.voice;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 音声による対話に必要な音声バックエンドを扱う。 */
public final class VoiceBackend implements AutoCloseable {
  /** 発話区間の検出。 */
  private final VoiceActivityDetector vad;

  /** 音声を文字へ変換する窓口。 */
  private final SpeechRecognizer recognizer;

  /** 終了済み。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 指定された値や利用先を使って、VoiceBackendを初期化する。 */
  public VoiceBackend(VoiceActivityDetector vad, SpeechRecognizer recognizer) {
    this.vad = Objects.requireNonNull(vad);
    this.recognizer = Objects.requireNonNull(recognizer);
  }

  /** 音声による対話で用いる発話区間の検出を返す。 */
  public VoiceActivityDetector vad() {
    return vad;
  }

  /** 音声による対話で用いるrecognizerを返す。 */
  public SpeechRecognizer recognizer() {
    return recognizer;
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        recognizer.close();
      } finally {
        vad.close();
      }
    }
  }
}
