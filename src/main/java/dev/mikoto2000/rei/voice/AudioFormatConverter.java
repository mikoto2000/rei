package dev.mikoto2000.rei.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** 音声による対話に必要な音声形式Converterを扱う。 */
public final class AudioFormatConverter {
  /** PCM音声16。 */
  public static final AudioFormat PCM16 = new AudioFormat(16000, 16, 1, true, false);

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private AudioFormatConverter() {}

  /** 利用先へ渡せるよう、PCM音声16の形式に変換する。 */
  public static AudioInputStream toPcm16(AudioInputStream input) {
    if (input.getFormat().matches(PCM16)) return input;
    if (!AudioSystem.isConversionSupported(PCM16, input.getFormat()))
      throw new IllegalArgumentException(
          "Selected audio format cannot be converted to 16kHz mono PCM16");
    return AudioSystem.getAudioInputStream(PCM16, input);
  }
}
