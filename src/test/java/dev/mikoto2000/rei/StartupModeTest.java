package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StartupModeTest {

  @Test
  void defaultsToShellAndRecognizesTuiOption() {
    assertEquals(StartupMode.SHELL, StartupMode.from(new String[0]));
    assertEquals(StartupMode.TUI, StartupMode.from(new String[] {"--tui"}));
    assertEquals(StartupMode.SHELL, StartupMode.from(new String[] {"--foo"}));
  }
}
