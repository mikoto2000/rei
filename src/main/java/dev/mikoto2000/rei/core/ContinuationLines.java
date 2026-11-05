package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded in-memory reuse of decoded rows after the source has been verified again. */
final class ContinuationLines {
  private static final int MAX_ENTRIES = 32;
  private static final long MAX_ESTIMATED_BYTES = 8L * 1024 * 1024;

  record Key(AgentRunContext run, Path root, Path path, String version, String charset) {}

  private record Entry(List<String> lines, long estimatedBytes) {}

  private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
  private long estimatedBytes;

  synchronized List<String> get(Key key) {
    var entry = entries.get(key);
    return entry == null ? null : entry.lines();
  }

  void put(Key key, List<String> lines) {
    // Charge UTF-16 storage plus room for each String, backing array and list slot.
    long weight = 0;
    for (String line : lines) {
      weight += 64L + 2L * line.length();
      if (weight > MAX_ESTIMATED_BYTES) return;
    }
    var entry = new Entry(List.copyOf(lines), weight);
    synchronized (this) {
      var previous = entries.put(key, entry);
      estimatedBytes += weight - (previous == null ? 0 : previous.estimatedBytes());
      while (entries.size() > MAX_ENTRIES || estimatedBytes > MAX_ESTIMATED_BYTES) {
        estimatedBytes -= entries.pollFirstEntry().getValue().estimatedBytes();
      }
    }
  }
}
