package dev.mikoto2000.rei.voice;

import java.time.Duration;

/**
 * 音声による対話に必要な音声実行環境上限一覧を扱う。
 *
 * <p>Finite CPU limits for turbo FP32. VAD stays responsive; cancellation interrupts owned startup.
 */
final class VoiceRuntimeLimits {
  /** 発話区間の検出起動時。 */
  static final Duration VAD_STARTUP = Duration.ofSeconds(30);

  /** ASR起動時。 */
  static final Duration ASR_STARTUP = Duration.ofMinutes(3);

  /** 発話区間の検出要求。 */
  static final Duration VAD_REQUEST = Duration.ofSeconds(5);

  /** ASR要求。 */
  static final Duration ASR_REQUEST = Duration.ofMinutes(3);

  // Includes both sequential workers, complete bundle hashing and microphone initialization.
  /** コマンド起動時。 */
  static final Duration COMMAND_STARTUP = Duration.ofMinutes(4);

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private VoiceRuntimeLimits() {}
}
