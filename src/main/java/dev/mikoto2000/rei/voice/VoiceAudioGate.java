package dev.mikoto2000.rei.voice;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Half-duplex playback suppression. Epochs also discard recognition started before playback. */
public final class VoiceAudioGate {
  public interface Playback extends AutoCloseable { @Override void close(); }
  private final LongSupplier ticks;
  private long epoch;
  private long until;
  private int playing;
  private boolean tail;
  public VoiceAudioGate(LongSupplier ticks) { this.ticks = Objects.requireNonNull(ticks); }
  public synchronized long epoch() { return epoch; }
  public synchronized boolean suppressed() {
    if (tail && ticks.getAsLong() - until >= 0) tail = false;
    return playing > 0 || tail;
  }
  public synchronized boolean allowed(long capturedEpoch) { return capturedEpoch == epoch && !suppressed(); }
  public synchronized boolean ifAllowed(long capturedEpoch, Runnable action) {
    if (!allowed(capturedEpoch)) return false;
    action.run(); return true;
  }
  public synchronized Playback playback(Duration duration) {
    Objects.requireNonNull(duration);
    if (duration.isNegative() || duration.compareTo(Duration.ofSeconds(3)) > 0)
      throw new IllegalArgumentException("Invalid playback tail");
    long nanos = duration.toNanos();
    playing++; epoch++;
    var closed = new AtomicBoolean();
    return () -> {
      if (!closed.compareAndSet(false, true)) return;
      synchronized (this) {
        playing--;
        long now = ticks.getAsLong();
        long remaining = tail ? Math.max(0, until - now) : 0;
        until = now + Math.max(remaining, nanos); tail = true;
      }
    };
  }
}
