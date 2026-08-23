package dev.mikoto2000.rei.core.command;

import java.io.IOException;
import java.util.List;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.SyntaxError;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;

import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliJLineCompleter;

/** Builds the canonical JLine input services for the Shell. */
public final class ReiLineReaderFactory {
  private static final List<String> BUILTINS = List.of("/exit", "/quit", "/help", "/version", "/paste");

  public record Session(LineReader reader, Completer completer) { }

  private ReiLineReaderFactory() { }

  public static Session create(Terminal terminal, CommandLine command) throws IOException {
    try {
      ReiPaths.ensureParentDirectoryExists(ReiPaths.historyFilePath());
    } catch (Exception exception) {
      throw new IOException("履歴ファイル用ディレクトリの作成に失敗しました: " + ReiPaths.historyFilePath(), exception);
    }
    Completer completer = completer(command);
    LineReader reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(completer)
        .variable(LineReader.HISTORY_FILE, ReiPaths.historyFilePath())
        .variable(LineReader.HISTORY_SIZE, 1000)
        .variable(LineReader.HISTORY_FILE_SIZE, 1000)
        .build();
    reader.setOpt(LineReader.Option.BRACKETED_PASTE);
    return new Session(reader, completer);
  }

  public static Completer completer(CommandLine command) {
    return new SlashCompleter(new PicocliJLineCompleter(command.getCommandSpec()),
        command.getSubcommands().keySet().stream().sorted().toList());
  }

  private static final class SlashCompleter implements Completer {
    private final Completer delegate;
    private final List<String> rootCommands;
    private final Parser parser = new DefaultParser();

    private SlashCompleter(Completer delegate, List<String> rootCommands) {
      this.delegate = delegate;
      this.rootCommands = rootCommands;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
      String raw = line.line();
      if (raw == null || !raw.startsWith("/")) return;
      if (!raw.substring(1).contains(" ")) {
        BUILTINS.stream().filter(value -> value.startsWith(raw)).forEach(value -> candidates.add(new Candidate(value)));
        rootCommands.stream().map(value -> "/" + value).filter(value -> value.startsWith(raw))
            .forEach(value -> candidates.add(new Candidate(value)));
        return;
      }
      if (raw.equals("/project add") || raw.startsWith("/project add ")) {
        ProjectAddDirectoryCompletion.complete(raw, ProjectService.currentProjectOrStartupDirectory())
            .forEach(value -> candidates.add(new Candidate(value)));
        return;
      }
      try {
        String stripped = raw.length() <= 1 ? "" : raw.substring(1);
        delegate.complete(reader,
            parser.parse(stripped, Math.max(0, line.cursor() - 1), Parser.ParseContext.COMPLETE), candidates);
      } catch (SyntaxError ignored) { }
    }
  }
}
