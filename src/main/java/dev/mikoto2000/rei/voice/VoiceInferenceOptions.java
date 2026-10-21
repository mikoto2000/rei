package dev.mikoto2000.rei.voice;

/**
 * 音声による対話で受け渡す音声推定選択可能な設定を表す。
 *
 * <p>Bounded CPU inference tuning, independent of VAD sample timing and model identity.
 *
 * @param threads スレッド一覧
 * @param tailFrames 末尾観測単位一覧
 */
public record VoiceInferenceOptions(int threads, int tailFrames) {
  /** 受け取った情報からVoiceInferenceOptionsを作成する。 */
  public VoiceInferenceOptions {
    if (threads < 1 || threads > 4 || tailFrames < 250 || tailFrames > 2000)
      throw new IllegalArgumentException("ASR threads must be 1..4; tail frames must be 250..2000");
  }

  /** 音声による対話で用いる既定値を返す。 */
  public static VoiceInferenceOptions defaults() {
    return new VoiceInferenceOptions(4, 1000);
  }
}
