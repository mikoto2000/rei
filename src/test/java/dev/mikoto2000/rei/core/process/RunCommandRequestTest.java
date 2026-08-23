package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RunCommandRequestTest {

  @Test
  void defaultsExecutionModeToAuto() {
    RunCommandRequest request = new RunCommandRequest("echo hello", null, null);
    assertEquals(CommandExecutionMode.AUTO, request.mode());
  }

  @Test
  void acceptsEveryExecutionModeCaseInsensitively() {
    assertEquals(CommandExecutionMode.AUTO, new RunCommandRequest("cmd", "AUTO", null).mode());
    assertEquals(CommandExecutionMode.FOREGROUND, new RunCommandRequest("cmd", "foreground", null).mode());
    assertEquals(CommandExecutionMode.BACKGROUND, new RunCommandRequest("cmd", "background", null).mode());
  }

  @Test
  void rejectsBlankCommandAndUnknownMode() {
    assertThrows(IllegalArgumentException.class, () -> new RunCommandRequest(" ", "auto", null));
    assertThrows(IllegalArgumentException.class, () -> new RunCommandRequest("cmd", "detached", null));
  }
}
