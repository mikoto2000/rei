package dev.mikoto2000.rei.ui.shell;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

class JLineShellEventOutputTest {
  @Test
  void longStreamingOutputIsVisibleBeforeTheMessageCompletes() {
    LineReader reader = mock(LineReader.class);
    Terminal terminal = mock(Terminal.class);
    when(reader.getTerminal()).thenReturn(terminal);
    when(terminal.writer()).thenReturn(new java.io.PrintWriter(System.out));
    when(reader.isReading()).thenReturn(true);
    JLineShellEventOutput output = new JLineShellEventOutput(reader);
    String chunk = "streaming ".repeat(10);
    output.print(chunk);
    output.flush();
    verify(reader).printAbove(chunk);
    output.print("tail"); output.println("");
    verify(reader).printAbove("tail");
  }
  @Test
  void buffersStreamingAndUsesPrintAboveWhileInputIsBeingEdited() {
    LineReader reader = mock(LineReader.class);
    Terminal terminal = mock(Terminal.class);
    when(reader.getTerminal()).thenReturn(terminal);
    when(terminal.writer()).thenReturn(new java.io.PrintWriter(System.out));
    when(reader.isReading()).thenReturn(true);
    JLineShellEventOutput output = new JLineShellEventOutput(reader);

    output.print("既存");
    output.print("入力");
    output.println("");

    verify(reader).printAbove("既存入力");
  }
}
