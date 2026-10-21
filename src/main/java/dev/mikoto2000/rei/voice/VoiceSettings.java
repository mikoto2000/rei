package dev.mikoto2000.rei.voice;

/**
 * 音声による対話で受け渡す音声設定を表す。
 *
 * <p>Sample-count timing at 16kHz; no second wall-clock silence timer.
 *
 * @param threshold 判定の境界値
 * @param preRollMs preRollミリ秒
 * @param minSpeechMs 最小発話ミリ秒
 * @param silenceMs 無音ミリ秒
 * @param maxSpeechMs 最大発話ミリ秒
 * @param tailMs 末尾ミリ秒
 */
public record VoiceSettings(
    float threshold, int preRollMs, int minSpeechMs, int silenceMs, int maxSpeechMs, int tailMs) {
  /** 音声のサンプリング周波数／範囲。 */
  public static final int SAMPLE_RATE = 16000, WINDOW = 512;

  /** 受け取った情報からVoiceSettingsを作成する。 */
  public VoiceSettings {
    if (!Float.isFinite(threshold)
        || threshold <= 0
        || threshold > 1
        || preRollMs < 0
        || preRollMs > 2000
        || minSpeechMs < 1
        || silenceMs < 1
        || silenceMs > 10000
        || maxSpeechMs < minSpeechMs
        || maxSpeechMs > 25000
        || tailMs < 0
        || tailMs > silenceMs) throw new IllegalArgumentException("Invalid voice settings");
  }

  /** 音声による対話で用いる既定値を返す。 */
  public static VoiceSettings defaults() {
    return new VoiceSettings(.5f, 300, 400, 1800, 25000, 200);
  }

  /** 音声による対話で用いるサンプル一覧を返す。 */
  public int samples(int milliseconds) {
    return milliseconds * 16;
  }
}
