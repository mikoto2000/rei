package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class ForegroundConsoleTest {
  @Test
  void commandInputPreservesEscButTurnsExitKeysIntoShellExit() throws Exception {
    var reader = mock(NonBlockingReader.class);
    when(reader.read(25)).thenReturn(27, 3, 4, -1);
    assertEquals(27, ForegroundConsole.readCommandInput(reader, 25));
    assertThrows(
        org.jline.reader.UserInterruptException.class,
        () -> ForegroundConsole.readCommandInput(reader, 25));
    assertThrows(
        org.jline.reader.EndOfFileException.class,
        () -> ForegroundConsole.readCommandInput(reader, 25));
    assertThrows(
        org.jline.reader.EndOfFileException.class,
        () -> ForegroundConsole.readCommandInput(reader, 25));
  }

  @Test
  void ctrlDAndCtrlCAndEofRestoreTerminalAndReturn() throws Exception {
    for (int exit : new int[] {4, 3, -1}) {
      var terminal = mock(Terminal.class);
      var reader = mock(NonBlockingReader.class);
      var attributes = new Attributes();
      when(terminal.reader()).thenReturn(reader);
      when(terminal.writer()).thenReturn(new PrintWriter(new StringWriter()));
      when(terminal.enterRawMode()).thenReturn(attributes);
      when(reader.read(200)).thenReturn(-2, 10, exit);
      ForegroundConsole.awaitExit(terminal, () -> true);
      verify(terminal).setAttributes(attributes);
      verify(reader, times(3)).read(200);
    }
  }

  @Test
  void remoteContextShutdownDoesNotWaitForConsoleInput() throws Exception {
    var terminal = mock(Terminal.class);
    when(terminal.writer()).thenReturn(new PrintWriter(new StringWriter()));
    ForegroundConsole.awaitExit(terminal, () -> false);
    verify(terminal, never()).reader();
  }

  @Test
  void nativeInterruptAlsoEndsForegroundLoop() throws Exception {
    var terminal = mock(Terminal.class);
    var reader = mock(NonBlockingReader.class);
    when(terminal.writer()).thenReturn(new PrintWriter(new StringWriter()));
    when(terminal.reader()).thenReturn(reader);
    var handler = new java.util.concurrent.atomic.AtomicReference<Terminal.SignalHandler>();
    doAnswer(
            i -> {
              handler.set(i.getArgument(1));
              return Terminal.SignalHandler.SIG_DFL;
            })
        .when(terminal)
        .handle(eq(Terminal.Signal.INT), any());
    when(reader.read(200))
        .thenAnswer(
            i -> {
              handler.get().handle(Terminal.Signal.INT);
              return -2;
            });
    ForegroundConsole.awaitExit(terminal, () -> true);
    verify(reader).read(200);
  }
}
