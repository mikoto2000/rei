package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ChatInputTest {
  @Test void preservesExplicitRunAndModeWithoutTreatingOptionsAsPrompt() {
    var input=ChatInput.parse(TerminalClient.commandWords("/chat --run run \"next instruction\""),"EXCLUSIVE");
    assertEquals("run",input.runId());assertEquals("next instruction",input.message());
    assertEquals("READ_ONLY",ChatInput.parse(TerminalClient.commandWords("/chat --mode read-only question"),"EXCLUSIVE").mode());
  }
  @Test void rejectsUnsupportedOrConflictingOptionsBeforeHttp() {
    assertThrows(IllegalArgumentException.class,()->ChatInput.parse(TerminalClient.commandWords("/chat --unknown value"),"EXCLUSIVE"));
    assertThrows(IllegalArgumentException.class,()->ChatInput.parse(TerminalClient.commandWords("/chat --mode conversation --run run message"),"EXCLUSIVE"));
    assertThrows(IllegalArgumentException.class,()->ChatInput.parse(TerminalClient.commandWords("/chat --mode exclusive --mode exclusive message"),"EXCLUSIVE"));
  }
}
