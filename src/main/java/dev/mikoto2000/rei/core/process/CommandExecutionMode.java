package dev.mikoto2000.rei.core.process;

import java.util.Locale;

/** 外部プロセスの管理におけるコマンド実行動作モードの選択肢。 */
public enum CommandExecutionMode {
  /** 自動。 */
  AUTO,
  /** 前面。 */
  FOREGROUND,
  /** バックグラウンド。 */
  BACKGROUND;

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  static CommandExecutionMode parse(String value) {
    if (value == null || value.isBlank()) return AUTO;
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("executionMode must be auto, foreground, or background");
    }
  }
}
