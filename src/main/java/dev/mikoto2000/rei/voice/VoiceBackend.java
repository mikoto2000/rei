package dev.mikoto2000.rei.voice;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
public final class VoiceBackend implements AutoCloseable {
  private final VoiceActivityDetector vad;
  private final SpeechRecognizer recognizer;
  private final AtomicBoolean closed = new AtomicBoolean();
  public VoiceBackend(VoiceActivityDetector vad, SpeechRecognizer recognizer) {
    this.vad = Objects.requireNonNull(vad);
    this.recognizer = Objects.requireNonNull(recognizer);
  }
  public VoiceActivityDetector vad() { return vad; }
  public SpeechRecognizer recognizer() { return recognizer; }
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try { recognizer.close(); } finally { vad.close(); }
    }
  }
}