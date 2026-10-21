package dev.mikoto2000.rei.voice;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 音声による対話で受け渡す発話区間を表す。
 *
 * @param id 識別子
 * @param samples サンプル一覧
 * @param createdAt 作成日時
 */
public record SpeechSegment(UUID id, float[] samples, Instant createdAt) {
  /** 受け取った情報からSpeechSegmentを作成する。 */
  public SpeechSegment {
    Objects.requireNonNull(id);
    Objects.requireNonNull(createdAt);
    if (samples == null || samples.length == 0 || samples.length > VoiceSettings.SAMPLE_RATE * 28)
      throw new IllegalArgumentException("Invalid speech segment size");
    samples = samples.clone();
  }

  /** 音声による対話で用いるサンプル一覧を返す。 */
  @Override
  public float[] samples() {
    return samples.clone();
  }
}
