package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;

import org.jline.reader.LineReader;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.mikoto2000.rei.core.command.ReiLineReaderFactory;
import dev.mikoto2000.rei.summarize.SummarizationException;
import dev.mikoto2000.rei.summarize.SummaryResult;
import dev.mikoto2000.rei.summarize.command.SummarizeCommand;
import picocli.CommandLine;

class ReiApplicationCommandOutputTest {

  @org.junit.jupiter.api.io.TempDir
  java.nio.file.Path subagentDirectory;

  @CommandLine.Command(name = "rei", subcommands = dev.mikoto2000.rei.subagent.SubAgentCommand.class)
  static class SubagentRoot { }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = { "UTF-8", "windows-31j" })
  void feedUsageKeepsTerminalWriterWhenConsoleStreamsAreRouted(String encoding) {
    CommandLine root = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    root.addSubcommand("feed", new dev.mikoto2000.rei.feed.command.FeedCommand());
    var output = configureOutput(root, encoding);
    var writer = root.getErr();
    try (var console = new dev.mikoto2000.rei.ui.shell.AgentConsoleSession()) {
      assertEquals(2, root.execute("feed"));
      org.assertj.core.api.Assertions.assertThat(output.toString(Charset.forName(encoding)))
          .contains("RSS/Atom フィードを操作します", "フィードを追加します");
      org.assertj.core.api.Assertions.assertThat(root.getErr()).isSameAs(writer);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = { "UTF-8", "windows-31j" })
  void feedUsageUsesActualTerminalWithConsoleEncoding(String encoding) throws Exception {
    var charset = Charset.forName(encoding);
    var output = new ByteArrayOutputStream();
    try (var console = new java.io.PrintStream(output, true, charset);
        var terminal = ReiApplication.terminalBuilder(console).system(false).dumb(true)
            .type(Terminal.TYPE_DUMB)
            .streams(new java.io.ByteArrayInputStream(new byte[0]), output).build()) {
      CommandLine root = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
      root.addSubcommand("feed", new dev.mikoto2000.rei.feed.command.FeedCommand());
      ReiApplication.configureCommandOutput(root, terminal);

      assertEquals(2, root.execute("feed"));
      terminal.flush();

      assertEquals(charset, terminal.encoding());
      org.assertj.core.api.Assertions.assertThat(output.toString(charset))
          .contains("RSS/Atom フィードを操作します", "フィードを追加します");
    }
  }

  @ParameterizedTest
  @CsvSource({ "UTF-8, false", "UTF-8, true", "windows-31j, false", "windows-31j, true" })
  void feedUsageUsesTerminalEncodingAfterCompleterInitialization(String encoding, boolean afterCompletion) {
    CommandLine root = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    root.addSubcommand("feed", new dev.mikoto2000.rei.feed.command.FeedCommand());
    var output = configureOutput(root, encoding);
    var completer = ReiLineReaderFactory.completer(root);
    if (afterCompletion) {
      var parser = new DefaultParser();
      for (String input : java.util.List.of("/feed ", "/feed item ")) {
        completer.complete(mock(LineReader.class),
            parser.parse(input, input.length(), Parser.ParseContext.COMPLETE), new ArrayList<>());
      }
    }
    assertEquals(2, root.execute("feed"));
    org.assertj.core.api.Assertions.assertThat(output.toString(Charset.forName(encoding)))
        .contains("Missing required subcommand", "RSS/Atom フィードを操作します", "フィードを追加します",
            "登録済みフィードを一覧します");
    output.reset();
    assertEquals(2, root.execute("feed", "item"));
    org.assertj.core.api.Assertions.assertThat(output.toString(Charset.forName(encoding)))
        .contains("個別記事を操作します");
  }

  @ParameterizedTest
  @CsvSource({ "UTF-8, false", "UTF-8, true", "windows-31j, false", "windows-31j, true" })
  void subagentUsesTerminalEncodingAfterCompletion(String encoding, boolean afterCompletion) throws Exception {
    java.nio.file.Files.writeString(subagentDirectory.resolve("reviewer.yaml"), """
        id: reviewer
        name: レビュー担当
        description: コードと設計を独立して検証します。
        systemPrompt: レビューしてください。
        tools: []
        maxSteps: 2
        timeout: 30s
        """);
    var policy = new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(java.util.Set.of());
    var loader = new dev.mikoto2000.rei.subagent.SubAgentDefinitionLoader(policy, model -> false);
    var registry = new dev.mikoto2000.rei.subagent.SubAgentRegistry(subagentDirectory, loader);
    assertEquals(java.util.List.of(), registry.reload());
    var bean = new dev.mikoto2000.rei.subagent.SubAgentCommand(registry, loader, policy);
    CommandLine root = new CommandLine(new SubagentRoot(), new CommandLine.IFactory() {
      public <K> K create(Class<K> type) throws Exception {
        return type == dev.mikoto2000.rei.subagent.SubAgentCommand.class ? type.cast(bean)
            : CommandLine.defaultFactory().create(type);
      }
    });
    var output = configureOutput(root, encoding);
    // Shell startup constructs the completer after wiring the terminal, even before the first Tab.
    var completer = ReiLineReaderFactory.completer(root);
    if (afterCompletion) {
      var parser = new DefaultParser();
      for (String input : java.util.List.of("/subagent ", "/subagent show ")) {
        completer.complete(mock(LineReader.class), parser.parse(input, input.length(), Parser.ParseContext.COMPLETE), new ArrayList<>());
      }
    }
    // A shared Spring command can be materialized again, rebinding its @Spec to a default writer.
    // The active Shell must keep using its captured JLine writer even after that happens.
    new CommandLine(bean).setOut(new PrintWriter(new java.io.StringWriter()));
    for (String[] arguments : java.util.List.of(new String[]{"subagent"}, new String[]{"subagent", "list"},
        new String[]{"subagent", "show", "reviewer"})) {
      output.reset();
      assertEquals(0, root.execute(arguments));
      org.assertj.core.api.Assertions.assertThat(output.toString(Charset.forName(encoding)))
          .contains("レビュー担当", "コードと設計を独立して検証します。");
    }
    output.reset();
    assertEquals(2, root.execute("subagent", "show", "存在しない担当"));
    org.assertj.core.api.Assertions.assertThat(output.toString(Charset.forName(encoding))).contains("存在しない担当");
  }

  @ParameterizedTest
  @CsvSource({ "UTF-8, false", "UTF-8, true", "windows-31j, false", "windows-31j, true" })
  void summaryUsesTerminalEncodingAndFlushes(String encoding, boolean afterCompletion) {
    String summary = "ORM の責務を整理します。日本語の要約結果です。";
    CommandLine root = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    root.addSubcommand("summarize", new SummarizeCommand(uri -> new SummaryResult(uri, summary, null)));
    ByteArrayOutputStream output = configureOutput(root, encoding);

    if (afterCompletion) completeSummary(root);

    assertEquals(0, root.execute("summarize", "https://example.com/article"));

    assertEquals(summary + System.lineSeparator(), output.toString(Charset.forName(encoding)));
  }

  @ParameterizedTest
  @CsvSource({ "UTF-8, false", "UTF-8, true", "windows-31j, false", "windows-31j, true" })
  void summaryErrorUsesTerminalEncodingAndFlushes(String encoding, boolean afterCompletion) {
    CommandLine root = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    root.addSubcommand("summarize", new SummarizeCommand(uri -> {
      throw new SummarizationException("EMPTY_CONTENT", "本文が空です");
    }));
    ByteArrayOutputStream output = configureOutput(root, encoding);

    if (afterCompletion) completeSummary(root);

    assertEquals(1, root.execute("summarize", "https://example.com/article"));

    assertEquals("要約に失敗しました: 本文が空です" + System.lineSeparator(),
        output.toString(Charset.forName(encoding)));
  }

  private ByteArrayOutputStream configureOutput(CommandLine commandLine, String encoding) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Terminal terminal = mock(Terminal.class);
    when(terminal.writer()).thenReturn(new PrintWriter(new OutputStreamWriter(output, Charset.forName(encoding))));
    ReiApplication.configureCommandOutput(commandLine, terminal);
    return output;
  }

  private void completeSummary(CommandLine commandLine) {
    var completer = ReiLineReaderFactory.completer(commandLine);
    var parser = new DefaultParser();
    String input = "/summarize ";
    for (int i = 0; i < 2; i++) {
      completer.complete(mock(LineReader.class),
          parser.parse(input, input.length(), Parser.ParseContext.COMPLETE),
          new ArrayList<>());
    }
  }
}
