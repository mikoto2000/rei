package dev.mikoto2000.rei.voice;
public interface SpeechRecognizer extends AutoCloseable {
  String recognize(SpeechSegment segment) throws Exception;
  void close();
}