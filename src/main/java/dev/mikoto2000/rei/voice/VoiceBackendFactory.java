package dev.mikoto2000.rei.voice;
@FunctionalInterface
public interface VoiceBackendFactory {
  VoiceBackend open(VoiceSettings settings) throws Exception;
}