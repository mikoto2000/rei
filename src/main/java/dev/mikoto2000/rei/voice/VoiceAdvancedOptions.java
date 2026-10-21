package dev.mikoto2000.rei.voice;

/**
 * 音声による対話で受け渡す音声Advanced選択可能な設定を表す。
 *
 * <p>Explicit opt-ins; wake words never authenticate a speaker or authorize a tool.
 *
 * @param wakeEnabled 待機の解除有効状態
 * @param wakeWord 待機の解除語句
 * @param interruptEnabled 中断有効状態
 * @param ttsEnabled tts有効状態
 * @param ttsVoice tts音声
 * @param echoTailMs echo末尾ミリ秒
 */
public record VoiceAdvancedOptions(
    boolean wakeEnabled,
    String wakeWord,
    boolean interruptEnabled,
    boolean ttsEnabled,
    String ttsVoice,
    int echoTailMs) {
  /** 受け取った情報からVoiceAdvancedOptionsを作成する。 */
  public VoiceAdvancedOptions {
    if (wakeWord == null || wakeWord.length() > 64 || (wakeEnabled && wakeWord.isBlank()))
      throw new IllegalArgumentException("Wake word must contain 1..64 characters when enabled");
    if (ttsVoice == null || ttsVoice.length() > 256 || (ttsEnabled && ttsVoice.isBlank()))
      throw new IllegalArgumentException("Select an installed voice before enabling TTS");
    if (echoTailMs < 250 || echoTailMs > 3000)
      throw new IllegalArgumentException("Echo tail must be 250..3000 ms");
  }

  /** 音声による対話で用いる既定値を返す。 */
  public static VoiceAdvancedOptions defaults() {
    return new VoiceAdvancedOptions(
        false, "れい", false, false, "Microsoft Haruka Desktop - Japanese", 800);
  }
}
