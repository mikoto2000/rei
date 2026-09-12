package dev.mikoto2000.rei.core.chat;

import java.util.ArrayDeque;
import java.util.List;

/** A run-owned mailbox. Closing and accepting input share one linearization point. */
public final class UserInterventionQueue {
  public record Entry(String id, String text) {}
  private final ArrayDeque<Entry> pending = new ArrayDeque<>();
  private final java.util.function.Consumer<Entry> received;
  private boolean finished;
  public UserInterventionQueue() { this(entry -> {}); }
  public UserInterventionQueue(java.util.function.Consumer<Entry> received) { this.received = received; }

  public synchronized boolean offer(String text) {
    if (finished) return false;
    var entry = new Entry(java.util.UUID.randomUUID().toString(), java.util.Objects.requireNonNull(text));
    pending.addLast(entry);
    received.accept(entry);
    return true;
  }

  public synchronized List<String> drain() {
    return drainEntries().stream().map(Entry::text).toList();
  }

  public synchronized List<Entry> drainEntries() {
    var result = List.copyOf(pending);
    pending.clear();
    return result;
  }

  public synchronized boolean finishIfEmpty() {
    if (!pending.isEmpty()) return false;
    finished = true;
    return true;
  }

  public synchronized int discardAndFinish() {
    int count = pending.size();
    pending.clear();
    finished = true;
    return count;
  }
}
