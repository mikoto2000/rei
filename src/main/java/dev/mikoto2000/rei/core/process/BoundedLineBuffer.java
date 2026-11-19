package dev.mikoto2000.rei.core.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 外部プロセスの管理に必要な上限内に収めた行一時保持領域を扱う。 */
class BoundedLineBuffer {
  /** 読み取り途中と保持済みの各行に適用する文字数上限。 */
  static final int MAX_LINE_CHARACTERS = 8192;

  /** 各出力ストリームで保持する文字数の総量。 */
  static final int MAX_CHARACTERS = 256 * 1024;

  private static final String TRUNCATED = "... [truncated]";

  private int characters;

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
    if (line.length() > MAX_LINE_CHARACTERS) {
      line = line.substring(0, MAX_LINE_CHARACTERS - TRUNCATED.length()) + TRUNCATED;
    }
    while (lines.size() >= capacity || characters + line.length() > MAX_CHARACTERS) {
      characters -= lines.removeFirst().length();
    }
    lines.addLast(line);
    characters += line.length();
  }

  /** 外部プロセスの管理で用いる末尾を返す。 */
  synchronized List<String> tail(int count) {
    int safeCount = count <= 0 ? capacity : Math.min(count, capacity);
    ArrayList<String> snapshot = new ArrayList<>(lines);
    int from = Math.max(0, snapshot.size() - safeCount);
    return List.copyOf(snapshot.subList(from, snapshot.size()));
  }
}
