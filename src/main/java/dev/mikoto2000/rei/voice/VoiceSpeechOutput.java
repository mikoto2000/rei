package dev.mikoto2000.rei.voice;

import java.util.function.BooleanSupplier;

/**
 * 音声による対話に必要な音声発話出力を扱う。
 *
 * <p>Local output must poll selection/cancellation and stop only its own playback.
 */
@FunctionalInterface
public interface VoiceSpeechOutput extends AutoCloseable {
  /** 音声による対話に関する読み上げを処理する。 */
  void speak(String voice, String text, BooleanSupplier current) throws Exception;

  /** 実行中の処理を停止する。 */
  default void stop() {}

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  default void close() {
    stop();
  }
}
