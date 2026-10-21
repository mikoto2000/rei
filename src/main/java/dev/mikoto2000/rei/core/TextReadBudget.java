package dev.mikoto2000.rei.core;

import java.util.ArrayList;
import java.util.List;

/**
 * テキスト読み取りの使用量と上限を管理する。
 *
 * <p>Bounds source payload bytes, including UTF-8 multibyte code points and enormous lines.
 */
final class TextReadBudget {
  /** バイト数の上限。 */
  static final int MAX_BYTES = 64 * 1024;

  /**
   * 共通の実行基盤で受け渡す取り出す範囲を表す。
   *
   * @param lines 行一覧
   * @param nextLine 次行
   * @param nextOffset 次開始位置
   * @param truncated 省略の有無
   * @param bytes バイト数
   */
  record Slice(List<String> lines, int nextLine, int nextOffset, boolean truncated, int bytes) {}

  /** 残量。 */
  private int remaining;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  TextReadBudget() {
    this(MAX_BYTES);
  }

  /** 指定された値や利用先を使って、TextReadBudgetを初期化する。 */
  TextReadBudget(int bytes) {
    remaining = Math.max(0, Math.min(MAX_BYTES, bytes));
  }

  /** 共通の実行基盤で用いる残量を返す。 */
  int remaining() {
    return remaining;
  }

  /** 共通の実行基盤において、予約に該当するか判定する。 */
  boolean reserve(int bytes) {
    if (bytes < 0 || bytes > remaining) return false;
    remaining -= bytes;
    return true;
  }

  /** 共通の実行基盤で用いる取り出しを返す。 */
  Slice take(List<String> source, int start, int end, int offset, int maxLines) {
    var out = new ArrayList<String>();
    int used = 0;
    int line = start;
    int nextOffset = offset;
    while (line < end && out.size() < maxLines && remaining > 0) {
      String value = source.get(line);
      if (nextOffset < 0
          || nextOffset > value.length()
          || nextOffset > 0
              && nextOffset < value.length()
              && Character.isLowSurrogate(value.charAt(nextOffset)))
        throw new IllegalArgumentException("Invalid continuation offset");
      int index = nextOffset;
      int cost = 0;
      while (index < value.length()) {
        int cp = value.codePointAt(index);
        int size = cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4;
        if (cost + size + 1 > remaining) break;
        cost += size;
        index += Character.charCount(cp);
      }
      if (index == nextOffset && index < value.length()) break;
      out.add(value.substring(nextOffset, index));
      remaining -= cost + 1;
      used += cost + 1;
      if (index < value.length()) {
        nextOffset = index;
        break;
      }
      line++;
      nextOffset = 0;
    }
    return new Slice(List.copyOf(out), line + 1, nextOffset, line < end, used);
  }
}
