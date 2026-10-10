package dev.mikoto2000.rei.voice;

/** Explicit opt-ins; wake words never authenticate a speaker or authorize a tool. */
public record VoiceAdvancedOptions(boolean wakeEnabled, String wakeWord, boolean interruptEnabled,
    boolean ttsEnabled, String ttsVoice, int echoTailMs) {
  public VoiceAdvancedOptions {
    if (wakeWord == null || wakeWord.length() > 64 || (wakeEnabled && wakeWord.isBlank()))
      throw new IllegalArgumentException("Wake word must contain 1..64 characters when enabled");
    if (ttsVoice == null || ttsVoice.length() > 256 || (ttsEnabled && ttsVoice.isBlank()))
      throw new IllegalArgumentException("Select an installed voice before enabling TTS");
    if (echoTailMs < 250 || echoTailMs > 3000)
      throw new IllegalArgumentException("Echo tail must be 250..3000 ms");
  }
  public static VoiceAdvancedOptions defaults() {
    return new VoiceAdvancedOptions(false, "れい", false, false,
        "Microsoft Haruka Desktop - Japanese", 800);
  }
}
