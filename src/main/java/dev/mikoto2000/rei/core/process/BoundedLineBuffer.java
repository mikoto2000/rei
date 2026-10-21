package dev.mikoto2000.rei.core.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 外部プロセスの管理に必要な上限内に収めた行一時保持領域を扱う。 */
class BoundedLineBuffer {
  /** 許容件数。 */
  private final int capacity;

  /** 行一覧。 */
  private final ArrayDeque<String> lines = new ArrayDeque<>();

  /** 指定された値や利用先を使って、BoundedLineBufferを初期化する。 */
  BoundedLineBuffer(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  synchronized void add(String line) {
    if (line == null) {
      return;
    }
    while (lines.size() >= capacity) {
      lines.removeFirst();
    }
    lines.addLast(line);
  }

  /** 外部プロセスの管理で用いる末尾を返す。 */
  synchronized List<String> tail(int count) {
    int safeCount = count <= 0 ? capacity : Math.min(count, capacity);
    ArrayList<String> snapshot = new ArrayList<>(lines);
    int from = Math.max(0, snapshot.size() - safeCount);
    return List.copyOf(snapshot.subList(from, snapshot.size()));
  }
}
