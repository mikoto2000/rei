package dev.mikoto2000.rei;

import java.util.Arrays;

enum StartupMode {
  SHELL,
  TUI;

  static StartupMode from(String[] args) {
    return args != null && Arrays.asList(args).contains("--tui") ? TUI : SHELL;
  }
}
