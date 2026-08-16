package dev.mikoto2000.rei.core.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

class BoundedLineBuffer {
  private final int capacity;
  private final ArrayDeque<String> lines = new ArrayDeque<>();

  BoundedLineBuffer(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  synchronized void add(String line) {
    if (line == null) {
      return;
    }
    while (lines.size() >= capacity) {
      lines.removeFirst();
    }
    lines.addLast(line);
  }

  synchronized List<String> tail(int count) {
    int safeCount = count <= 0 ? capacity : Math.min(count, capacity);
    ArrayList<String> snapshot = new ArrayList<>(lines);
    int from = Math.max(0, snapshot.size() - safeCount);
    return List.copyOf(snapshot.subList(from, snapshot.size()));
  }
}
