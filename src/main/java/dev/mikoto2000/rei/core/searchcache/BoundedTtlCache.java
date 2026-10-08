package dev.mikoto2000.rei.core.searchcache;

import java.time.*;
import java.util.*;
import java.util.function.ToLongFunction;

/** Thread-safe FIFO with finite retention and both entry and resident-weight bounds. */
public final class BoundedTtlCache<K, V> {
  private record Entry<V>(V value, long weight, Instant expiresAt, long createdNanos) { }
  private final Duration ttl;
  private final long ttlNanos;
  private final int maximumEntries;
  private final long maximumBytes;
  private final Clock clock;
  private final ToLongFunction<? super V> weigher;
  private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>();
  private long bytes;
  public BoundedTtlCache(Duration ttl, int maximumEntries, long maximumBytes, Clock clock, ToLongFunction<? super V> weigher) {
    if (ttl == null || ttl.isNegative() || ttl.isZero() || maximumEntries < 1 || maximumBytes < 1)
      throw new IllegalArgumentException("Invalid cache bounds");
    this.ttl = ttl; this.maximumEntries = maximumEntries; this.maximumBytes = maximumBytes;
    this.clock = Objects.requireNonNull(clock); this.weigher = Objects.requireNonNull(weigher);
    ttlNanos = ttl.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0 ? Long.MAX_VALUE : ttl.toNanos();
  }
  public synchronized boolean put(K key, V value) {
    Objects.requireNonNull(key); Objects.requireNonNull(value);
    long weight = weigher.applyAsLong(value);
    if (weight < 0) throw new IllegalArgumentException("Negative cache weight");
    if (weight > maximumBytes) return false;
    purge(); remove(key);
    while (!entries.isEmpty() && (entries.size() >= maximumEntries || weight > maximumBytes - bytes)) {
      remove(entries.keySet().iterator().next());
    }
    entries.put(key, new Entry<>(value, weight, clock.instant().plus(ttl), System.nanoTime())); bytes += weight; return true;
  }
  public synchronized Optional<V> get(K key) {
    Entry<V> entry = entries.get(key);
    if (entry == null) return Optional.empty();
    if (expired(entry)) { remove(key); return Optional.empty(); }
    return Optional.of(entry.value());
  }
  public synchronized void remove(K key) { var removed = entries.remove(key); if (removed != null) bytes -= removed.weight(); }
  public synchronized void clear() { entries.clear(); bytes = 0; }
  public synchronized int size() { purge(); return entries.size(); }
  public synchronized long bytes() { purge(); return bytes; }
  private boolean expired(Entry<V> entry) {
    return !clock.instant().isBefore(entry.expiresAt()) || System.nanoTime() - entry.createdNanos() >= ttlNanos;
  }
  private void purge() {
    var iterator = entries.values().iterator();
    while (iterator.hasNext()) { var entry = iterator.next(); if (expired(entry)) { bytes -= entry.weight(); iterator.remove(); } }
  }
}
