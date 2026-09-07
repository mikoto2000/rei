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
