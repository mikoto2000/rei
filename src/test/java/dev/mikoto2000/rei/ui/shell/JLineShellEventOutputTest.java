package dev.mikoto2000.rei.ui.shell;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;

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
    verify(reader).printAbove(chunk.substring(0, 80));
    output.print("tail"); output.println("");
    verify(reader).printAbove(chunk.substring(80) + "tail");
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

  @Test void newlineFlushDoesNotCommitTheNextIncompleteMarkdownLine() {
    LineReader reader = mock(LineReader.class);
    Terminal terminal = mock(Terminal.class);
    when(reader.getTerminal()).thenReturn(terminal);
    when(terminal.writer()).thenReturn(new java.io.PrintWriter(System.out));
    when(terminal.getWidth()).thenReturn(120);
    when(reader.isReading()).thenReturn(true);
    var output = new JLineShellEventOutput(reader);
    output.print("## 直前の話題\n\n**embed"); output.flush();
    output.print("ding サーバー**について\n末尾"); output.flush();
    output.println("");
    var ordered = inOrder(reader);
    ordered.verify(reader).printAbove("## 直前の話題");
    ordered.verify(reader).printAbove("");
    ordered.verify(reader).printAbove("**embedding サーバー**について");
    ordered.verify(reader).printAbove("末尾");
    verify(reader, never()).printAbove("## 直前の話題\n\n**embed");
  }

  @Test void wrapsWideCharactersAtTerminalColumnsWithoutAnExtraBlankLine() {
    LineReader reader = mock(LineReader.class);
    Terminal terminal = mock(Terminal.class);
    when(reader.getTerminal()).thenReturn(terminal);
    when(terminal.writer()).thenReturn(new java.io.PrintWriter(System.out));
    when(terminal.getWidth()).thenReturn(8);
    when(reader.isReading()).thenReturn(true);
    var output = new JLineShellEventOutput(reader);
    output.print("日本語の続き\n次"); output.flush(); output.println("");
    var ordered = inOrder(reader);
    ordered.verify(reader).printAbove("日本語の");
    ordered.verify(reader).printAbove("続き");
    ordered.verify(reader).printAbove("次");
    verify(reader, never()).printAbove("");
  }

  @Test void flushDrainsPendingTextWhenInputEditingEnds() {
    LineReader reader = mock(LineReader.class);
    Terminal terminal = mock(Terminal.class);
    var text = new java.io.StringWriter();
    when(reader.getTerminal()).thenReturn(terminal);
    when(terminal.writer()).thenReturn(new java.io.PrintWriter(text));
    when(reader.isReading()).thenReturn(true);
    var output = new JLineShellEventOutput(reader);
    output.print("pending");
    when(reader.isReading()).thenReturn(false);
    output.flush();
    org.assertj.core.api.Assertions.assertThat(text.toString()).isEqualTo("pending");
  }
}
