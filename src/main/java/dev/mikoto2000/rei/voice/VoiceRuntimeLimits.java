package dev.mikoto2000.rei.voice;
import java.time.Duration;
/** Finite CPU limits for turbo FP32. VAD stays responsive; cancellation interrupts owned startup. */
final class VoiceRuntimeLimits {
  static final Duration VAD_STARTUP=Duration.ofSeconds(30);
  static final Duration ASR_STARTUP=Duration.ofMinutes(3);
  static final Duration VAD_REQUEST=Duration.ofSeconds(5);
  static final Duration ASR_REQUEST=Duration.ofMinutes(3);
  // Includes both sequential workers, complete bundle hashing and microphone initialization.
  static final Duration COMMAND_STARTUP=Duration.ofMinutes(4);
  private VoiceRuntimeLimits() {}
}
