package dev.mikoto2000.rei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.command.UserInputService;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class ReiVersionCommandTest {
  @ParameterizedTest
  @ValueSource(strings = {"--version", "-V"})
  void shellVersionPrintsApplicationVersionAndKeepsCommandParserUsable(String option) {
    var input = new UserInputService(new UserInputParser()).interpret(" /version ");
    assertThat(input.kind()).isEqualTo(UserInputService.Kind.VERSION);
    var output = new StringWriter();
    var errors = new StringWriter();
    var root = new RootCommand();
    var command =
        new CommandLine(
                root,
                new CommandLine.IFactory() {
                  @Override
                  public <K> K create(Class<K> type) throws Exception {
                    return type == dev.mikoto2000.rei.ui.shell.ChatCommand.class
                        ? type.cast(mock(dev.mikoto2000.rei.ui.shell.ChatCommand.class))
                        : CommandLine.defaultFactory().create(type);
                  }
                })
            .setOut(new PrintWriter(output))
            .setErr(new PrintWriter(errors));
    root.configureCommands(command);
    var app = mock(ReiApplication.class, org.mockito.Mockito.CALLS_REAL_METHODS);

    assertThat(app.executeWithOutputPolicy(command, option)).isZero();
    assertThat(output.toString())
        .startsWith("rei ")
        .doesNotContain("Usage:", "Commands:", "v1.0.0");
    assertThat(errors.toString()).isEmpty();
    String shellVersion = output.toString();
    assertThat(captureStartup(option)).isEqualTo(shellVersion);

    output.getBuffer().setLength(0);
    assertThat(app.executeWithOutputPolicy(command, "--help")).isZero();
    assertThat(output.toString()).contains("Usage:", "--help", "--version", "chat");
    assertThat(errors.toString()).isEmpty();
    assertThat(app.executeWithOutputPolicy(command, "-h")).isZero();
    assertThat(command.parseArgs("chat", "hello").subcommand().commandSpec().name())
        .isEqualTo("chat");
  }

  @Test
  void startupVersionAndHelpReturnBeforeSpringEvenWithMissingProject() throws Exception {
    String startupVersion = captureStartup("--version");
    assertThat(startupVersion)
        .startsWith("rei ")
        .doesNotContain("v1.0.0", "Starting ReiApplication", "AI Shell");
    assertThat(captureStartup("--version", "--project", "missing-version-project"))
        .isEqualTo(startupVersion);
    assertThat(captureStartup("--mode=server", "--version")).isEqualTo(startupVersion);
    assertThat(captureStartup("--help"))
        .contains("Usage: rei", "--project", "--version")
        .doesNotContain("Starting ReiApplication", "AI Shell");
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF-8", "windows-31j"})
  void versionUsesShellTerminalWriter(String encoding) {
    var output = new ByteArrayOutputStream();
    var terminal = mock(org.jline.terminal.Terminal.class);
    org.mockito.Mockito.when(terminal.writer())
        .thenReturn(
            new PrintWriter(
                new java.io.OutputStreamWriter(
                    output, java.nio.charset.Charset.forName(encoding))));
    var command = new CommandLine(new RootCommand());
    ReiApplication.configureCommandOutput(command, terminal);
    var writer = command.getOut();
    try (var console = new dev.mikoto2000.rei.ui.shell.AgentConsoleSession()) {
      assertThat(command.execute("--version")).isZero();
    }
    assertThat(output.toString(java.nio.charset.Charset.forName(encoding)))
        .isEqualTo(captureStartup("--version"));
    assertThat(command.getOut()).isSameAs(writer);
  }

  @Test
  void ordinaryStartupStillPassesSpringArgumentsThrough() {
    var options =
        StartupOptions.parse(new String[] {"--rei.embedding.enabled=false"}, Path.of("."));
    assertThat(options.helpRequested()).isFalse();
    assertThat(options.backend()).isFalse();
    assertThat(options.project()).isNull();
    assertThat(options.printHelpIfRequested()).isFalse();
  }

  private String captureStartup(String... args) {
    var bytes = new ByteArrayOutputStream();
    PrintStream previous = System.out;
    try (var capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      ReiApplication.main(args);
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    } finally {
      System.setOut(previous);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
