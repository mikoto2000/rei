package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserInputServiceTest {

  private final UserInputService inputs = new UserInputService(new UserInputParser());

  @Test
  void preservesCanonicalShellSpecialCommands() {
    assertEquals(UserInputService.Kind.EXIT, inputs.interpret("/exit").kind());
    assertEquals(UserInputService.Kind.EXIT, inputs.interpret(" /quit ").kind());
    assertEquals(UserInputService.Kind.HELP, inputs.interpret("/help").kind());
    assertEquals(UserInputService.Kind.VERSION, inputs.interpret("/version").kind());
    assertEquals(UserInputService.Kind.PASTE, inputs.interpret("/paste").kind());
  }

  @Test
  void preservesEmptyChatAndGeneralSlashBehavior() {
    assertEquals(UserInputService.Kind.EMPTY, inputs.interpret("  ").kind());
    assertEquals(UserInputService.Kind.CHAT, inputs.interpret(" hello ").kind());
    assertEquals(" hello ", inputs.interpret(" hello ").text());
    UserInputService.Input command = inputs.interpret("/project add \"C:\\work dir\"");
    assertEquals(UserInputService.Kind.COMMAND, command.kind());
    assertArrayEquals(new String[] {"project", "add", "C:\\work dir"}, command.arguments());
  }

  @Test
  void onlyExactSpecialCommandsReceiveSpecialMeaning() {
    assertEquals(UserInputService.Kind.COMMAND, inputs.interpret("/help extra").kind());
    assertEquals(UserInputService.Kind.COMMAND, inputs.interpret("/exit now").kind());
  }
}
