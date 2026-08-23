package dev.mikoto2000.rei.core.process;

import java.util.Locale;

public enum CommandExecutionMode {
  AUTO,
  FOREGROUND,
  BACKGROUND;

  static CommandExecutionMode parse(String value) {
    if (value == null || value.isBlank()) return AUTO;
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("executionMode must be auto, foreground, or background");
    }
  }
}
