package dev.mikoto2000.rei.voice;
public interface VoiceActivityDetector extends AutoCloseable {
  float probability(float[] frame) throws Exception;
  void close();
}