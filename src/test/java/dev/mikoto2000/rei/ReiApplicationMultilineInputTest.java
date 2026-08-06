package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;

class ReiApplicationMultilineInputTest {

  @Test
  void readPossiblyMultilineInput_returnsSingleLineAsIs() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);

    assertEquals("hello", app.readPossiblyMultilineInput("hello", reader));
  }

  @Test
  void readPossiblyMultilineInput_joinsContinuationLines() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("...> ")).thenReturn("world");

    assertEquals("hello" + System.lineSeparator() + "world", app.readPossiblyMultilineInput("hello\\", reader));
  }

  @Test
  void readPossiblyMultilineInput_supportsMultipleContinuationLines() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("...> ")).thenReturn("line2\\", "line3");

    assertEquals(
        "line1" + System.lineSeparator() + "line2" + System.lineSeparator() + "line3",
        app.readPossiblyMultilineInput("line1\\", reader));
  }

  @Test
  void splitCommandLineSupportsQuotedArguments() {
    ReiApplication app = newApp();

    assertEquals(
        java.util.List.of("project", "add", "C:\\path with space"),
        java.util.List.of(app.splitCommandLine("project add \"C:\\path with space\"")));
  }

  @Test
  void configureCommandOutputSetsPicocliWriters() {
    CommandLine commandLine = new CommandLine(new RootCommand());

    ReiApplication.configureCommandOutput(commandLine);

    assertNotNull(commandLine.getOut());
    assertNotNull(commandLine.getErr());
  }

  @Test
  void detectsInteractiveShellCommand() {
    ReiApplication app = newApp();

    assertEquals(true, app.isInteractiveShellCommand("sh"));
    assertEquals(false, app.isInteractiveShellCommand("chat"));
  }

  @Test
  void clearPendingInputAfterInteractiveShellClearsBufferAndDrainsTerminalReader() throws Exception {
    ReiApplication app = newApp();
    LineReader lineReader = Mockito.mock(LineReader.class);
    Buffer buffer = Mockito.mock(Buffer.class);
    Terminal terminal = Mockito.mock(Terminal.class);
    NonBlockingReader terminalReader = Mockito.mock(NonBlockingReader.class);
    when(lineReader.getBuffer()).thenReturn(buffer);
    when(terminal.reader()).thenReturn(terminalReader);
    when(terminalReader.read(10)).thenReturn((int) 't', NonBlockingReader.READ_EXPIRED);

    app.clearPendingInputAfterInteractiveShell(lineReader, terminal);

    verify(buffer).clear();
    verify(terminalReader, Mockito.times(2)).read(10);
  }

  @Test
  void executeInteractiveShellCommandPausesAndResumesTerminal() throws Exception {
    ReiApplication app = newApp();
    LineReader lineReader = Mockito.mock(LineReader.class);
    Buffer buffer = Mockito.mock(Buffer.class);
    Terminal terminal = Mockito.mock(Terminal.class);
    NonBlockingReader terminalReader = Mockito.mock(NonBlockingReader.class);
    when(lineReader.getBuffer()).thenReturn(buffer);
    when(terminal.canPauseResume()).thenReturn(true);
    when(terminal.reader()).thenReturn(terminalReader);
    when(terminalReader.read(10)).thenReturn(NonBlockingReader.READ_EXPIRED);
    CommandLine commandLine = new CommandLine(new NoopCommand());

    app.executeInteractiveShellCommand(commandLine, lineReader, terminal);

    verify(terminal).pause(true);
    verify(terminal).resume();
    verify(buffer).clear();
  }

  @Test
  void executeInteractiveShellCommandRunsWhenPauseResumeIsUnsupported() throws Exception {
    ReiApplication app = newApp();
    LineReader lineReader = Mockito.mock(LineReader.class);
    Buffer buffer = Mockito.mock(Buffer.class);
    Terminal terminal = Mockito.mock(Terminal.class);
    NonBlockingReader terminalReader = Mockito.mock(NonBlockingReader.class);
    when(lineReader.getBuffer()).thenReturn(buffer);
    when(terminal.canPauseResume()).thenReturn(false);
    when(terminal.reader()).thenReturn(terminalReader);
    when(terminalReader.read(10)).thenReturn(NonBlockingReader.READ_EXPIRED);
    CommandLine commandLine = new CommandLine(new NoopCommand());

    app.executeInteractiveShellCommand(commandLine, lineReader, terminal);

    verify(terminal, Mockito.never()).pause(true);
    verify(terminal, Mockito.never()).resume();
    verify(buffer).clear();
  }

  @CommandLine.Command(name = "noop")
  static class NoopCommand implements Runnable {
    @Override
    public void run() {
    }
  }

  private ReiApplication newApp() {
    AsyncVectorDocumentService asyncVectorDocumentService = Mockito.mock(AsyncVectorDocumentService.class);
    when(asyncVectorDocumentService.hasActiveEmbeddings()).thenReturn(false);
    return new ReiApplication(
        Mockito.mock(RootCommand.class),
        CommandLine.defaultFactory(),
        Mockito.mock(ModelHolderService.class),
        Mockito.mock(EscCancellationMonitor.class),
        Mockito.mock(CommandCancellationService.class),
        new CommandCompletionNotificationPolicy(),
        new CommandUserInputDisplayPolicy(),
        asyncVectorDocumentService,
        Mockito.mock(SoundNotificationService.class),
        Mockito.mock(ChatResponseNarrator.class));
  }
}
