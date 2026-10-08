package dev.mikoto2000.rei.http;

import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reference-counted host permits, removed when the last owner or waiter leaves. */
public final class HostAdmission {
  @FunctionalInterface public interface Lease extends AutoCloseable { @Override void close(); }
  public static final Lease NONE = () -> {};
  private static final class Entry {
    final Semaphore slots; int users;
    Entry(int maximum) { slots = new Semaphore(maximum, true); }
  }
  private final int maximum;
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  public HostAdmission(int maximum) {
    if (maximum < 1 || maximum > 3) throw new IllegalArgumentException("Invalid per-host admission limit");
    this.maximum = maximum;
  }
  public Lease acquire(String host, FetchOperation operation) {
    String key = host.toLowerCase(Locale.ROOT);
    Entry entry = entries.compute(key, (ignored, existing) -> {
      var value = existing == null ? new Entry(maximum) : existing; value.users++; return value;
    });
    boolean acquired = false;
    try {
      while (!acquired) { operation.check(); acquired = entry.slots.tryAcquire(50, TimeUnit.MILLISECONDS); }
      operation.check();
      var released = new AtomicBoolean();
      return () -> { if (released.compareAndSet(false, true)) { entry.slots.release(); release(key, entry); } };
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); if (acquired) entry.slots.release(); release(key, entry); throw new CancellationException();
    } catch (RuntimeException stopped) {
      if (acquired) entry.slots.release(); release(key, entry); throw stopped;
    }
  }
  private void release(String key, Entry expected) {
    entries.compute(key, (ignored, current) -> current == expected && --current.users == 0 ? null : current);
  }
  public int entries() { return entries.size(); }
}
