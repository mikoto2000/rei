package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

/** れいアプリケーションMultiline入力の結果と状態の契約を検証するテスト。 */
class ReiApplicationMultilineInputTest {

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"dumb", "dumb-color"})
  @org.junit.jupiter.api.Timeout(5)
  void pipedResumeLineIsAcceptedInsteadOfBecomingMultilineText(String type) throws Exception {
    String command = "/resume ca74f54d-9953-4223-a536-5d6927ca184c";
    try (var terminal =
        new org.jline.terminal.impl.DumbTerminal(
            "pipe",
            type,
            new java.io.ByteArrayInputStream(
                (command + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            new java.io.ByteArrayOutputStream(),
            java.nio.charset.StandardCharsets.UTF_8)) {
      var reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
      newApp().configureMultilineKeyBinding(reader);
      assertEquals(command, reader.readLine(""));
    }
  }

  /** 単一行形式を返すことを確認する。 */
  @Test
  void readPossiblyMultilineInput_returnsSingleLineAsIs() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);

    assertEquals("hello", app.readPossiblyMultilineInput("hello", reader));
  }

  /** れいの起動で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void readPossiblyMultilineInput_preservesTrailingCommandPathSeparator() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    for (String line :
        java.util.List.of(
            "/project cd F:\\",
            "/project cd .\\rei\\",
            " /project add \"F:\\My Documents\\",
            "/embed add F:\\docs\\")) {
      assertEquals(line, app.readPossiblyMultilineInput(line, reader));
    }
    Mockito.verifyNoInteractions(reader);
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void readPossiblyMultilineInput_joinsContinuationLines() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("...> ")).thenReturn("world");

    assertEquals(
        "hello" + System.lineSeparator() + "world",
        app.readPossiblyMultilineInput("hello\\", reader));
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void readPossiblyMultilineInput_supportsMultipleContinuationLines() {
    ReiApplication app = newApp();
    LineReader reader = Mockito.mock(LineReader.class);
    when(reader.readLine("...> ")).thenReturn("line2\\", "line3");

    assertEquals(
        "line1" + System.lineSeparator() + "line2" + System.lineSeparator() + "line3",
        app.readPossiblyMultilineInput("line1\\", reader));
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void splitCommandLineSupportsQuotedArguments() {
    ReiApplication app = newApp();

    assertEquals(
        java.util.List.of("project", "add", "C:\\path with space"),
        java.util.List.of(app.splitCommandLine("project add \"C:\\path with space\"")));
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void configureCommandOutputSetsPicocliWriters() {
    CommandLine commandLine = new CommandLine(new RootCommand());
    Terminal terminal = Mockito.mock(Terminal.class);
    when(terminal.writer()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));

    ReiApplication.configureCommandOutput(commandLine, terminal);

    assertNotNull(commandLine.getOut());
    assertNotNull(commandLine.getErr());
  }

  /** 実行一覧について、終端文字コードを維持することを確認する。 */
  @Test
  void runsKeepsTerminalEncodingWhenCommandSpecIsRebound() {
    var display = Mockito.mock(dev.mikoto2000.rei.ui.shell.ActiveRunDisplay.class);
    when(display.rows()).thenReturn(java.util.List.of("rei  RUNNING  01:35  全 md ファイルをレビューしてください"));
    var runs = new dev.mikoto2000.rei.ui.shell.RunsCommand(display);
    var command = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    command.addSubcommand("runs", runs);
    var bytes = new java.io.ByteArrayOutputStream();
    var encoding = java.nio.charset.Charset.forName("windows-31j");
    Terminal terminal = Mockito.mock(Terminal.class);
    when(terminal.writer())
        .thenReturn(new java.io.PrintWriter(new java.io.OutputStreamWriter(bytes, encoding)));
    ReiApplication.configureCommandOutput(command, terminal);
    // Rebinding must not replace the shell's writer with a default-encoding writer.
    var rebound = new CommandLine(runs);
    var wrongOutput = new java.io.StringWriter();
    rebound.setOut(new java.io.PrintWriter(wrongOutput));
    assertEquals(0, command.execute("runs"));
    org.junit.jupiter.api.Assertions.assertTrue(
        bytes.toString(encoding).contains("全 md ファイルをレビューしてください"));
    assertEquals("", wrongOutput.toString());
  }

  /** れいの起動の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void historyControlsBypassAgentExecutorAndCancellationMonitor() throws Exception {
    var app = newApp();
    var command = Mockito.mock(CommandLine.class);
    var terminal = Mockito.mock(Terminal.class);
    var executor = Mockito.mock(java.util.concurrent.ExecutorService.class);
    for (String[] args :
        java.util.List.of(
            new String[] {"history"},
            new String[] {"history", "show"},
            new String[] {"history", "search", "needle"})) {
      app.executeInterruptibly(command, terminal, executor, args);
      verify(command).execute(args);
    }
    Mockito.verifyNoInteractions(terminal, executor);
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void detectsInteractiveShellCommand() {
    ReiApplication app = newApp();

    assertEquals(true, app.isInteractiveShellCommand("sh"));
    assertEquals(false, app.isInteractiveShellCommand("tui"));
    assertEquals(false, app.isInteractiveShellCommand("chat"));
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void clearPendingInputAfterInteractiveShellClearsBufferAndDrainsTerminalReader()
      throws Exception {
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

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 検証条件を再現するためのNoopコマンド。 */
  @CommandLine.Command(name = "noop")
  static class NoopCommand implements Runnable {
    /** 検証用に、れいの起動の操作を実行する。 */
    @Override
    public void run() {}
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private ReiApplication newApp() {
    AsyncVectorDocumentService asyncVectorDocumentService =
        Mockito.mock(AsyncVectorDocumentService.class);
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
        Mockito.mock(ChatResponseNarrator.class),
        Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class));
  }
}
