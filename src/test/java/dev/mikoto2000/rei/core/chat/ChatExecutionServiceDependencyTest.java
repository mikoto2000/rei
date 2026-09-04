package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ChatExecutionServiceDependencyTest {

  @Test
  void chatExecutionServiceDoesNotDependOnShellUiOrSoundPresentationComponents() throws IOException {
    String source = Files.readString(Path.of("src/main/java/dev/mikoto2000/rei/core/chat/ChatExecutionService.java"));

    assertFalse(source.contains("dev.mikoto2000.rei.ui."));
    assertFalse(source.contains("ChatResponseNarrator"));
    assertFalse(source.contains("SoundNotificationService"));
    assertFalse(source.contains("SoundNotificationProperties"));
    assertFalse(source.contains("System.out"));
    assertFalse(source.contains("System.err"));
    assertFalse(source.contains("IO.print"));
    assertFalse(source.contains("IO.println"));
  }
}
