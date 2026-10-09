package dev.mikoto2000.rei.voice;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
/** Default events contain fixed diagnostic codes. Only explicit /voice test emits recognition text. */
public final class VoiceEventPublisher {
  public enum Type { STATE_CHANGED, SEGMENT_QUEUE_FULL, SHORT_DROPPED, MAX_DROPPED,
    RESULT_REJECTED, DIAGNOSTIC_RESULT, INPUT_REJECTED, BACKEND_FAILED, CAPTURE_FAILED, RECOGNITION_FAILED, RELEASE_FAILED }
  public record Event(Type type, String detail) {}
  private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
  public interface Subscription extends AutoCloseable { @Override void close(); }
  public Subscription subscribe(Consumer<Event> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }
  public void publish(Type type, String detail) {
    var event = new Event(type, detail);
    for (var listener : listeners) {
      try { listener.accept(event); } catch (RuntimeException ignored) {
        // A UI listener must not kill capture or native-resource cleanup.
      }
    }
  }
}