package dev.mikoto2000.rei.ui.tui;

import java.util.Optional;

/** TUI 固有の単一行入力状態。cursor は UTF-16 の code-point 境界で保持する。 */
final class AgentTuiInput {

  private final StringBuilder text = new StringBuilder();
  private int cursor;

  String text() {
    return text.toString();
  }

  String textBeforeCursor() {
    return text.substring(0, cursor);
  }

  int cursor() {
    return cursor;
  }

  void insert(int codePoint) {
    String value = Character.toString(codePoint);
    text.insert(cursor, value);
    cursor += value.length();
  }

  void backspace() {
    if (cursor == 0) {
      return;
    }
    int previous = text.offsetByCodePoints(cursor, -1);
    text.delete(previous, cursor);
    cursor = previous;
  }

  void delete() {
    if (cursor == text.length()) {
      return;
    }
    int next = text.offsetByCodePoints(cursor, 1);
    text.delete(cursor, next);
  }

  void left() {
    if (cursor > 0) {
      cursor = text.offsetByCodePoints(cursor, -1);
    }
  }

  void right() {
    if (cursor < text.length()) {
      cursor = text.offsetByCodePoints(cursor, 1);
    }
  }

  void home() {
    cursor = 0;
  }

  void end() {
    cursor = text.length();
  }

  Optional<String> submit() {
    String value = text.toString();
    if (value.isBlank()) {
      return Optional.empty();
    }
    text.setLength(0);
    cursor = 0;
    return Optional.of(value);
  }
}
