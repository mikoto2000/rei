package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ChatCommandDependencyTest {

  @Test
  void chatCommandDoesNotWriteUserFacingOutputDirectlyToStandardOutput() throws IOException {
    String source = Files.readString(Path.of("src/main/java/dev/mikoto2000/rei/ui/shell/ChatCommand.java"));

    assertFalse(source.contains("System.out"));
    assertFalse(source.contains("System.err"));
    assertFalse(source.contains("IO.print"));
    assertFalse(source.contains("IO.println"));
  }
}
