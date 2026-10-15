package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.completion.*;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.ui.shell.JLineCompletionAdapter;
import java.io.IOException;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.Parser;
import org.jline.terminal.Terminal;
import picocli.CommandLine;

/** Builds the canonical JLine input services for the Shell. */
public final class ReiLineReaderFactory {
  public record Session(LineReader reader, Completer completer) {}

  private ReiLineReaderFactory() {}

  public static Session create(Terminal terminal, CommandLine command) throws IOException {
    return create(terminal, command, completionEngine());
  }

  public static Session create(Terminal terminal, CommandLine command, CompletionEngine engine)
      throws IOException {
    Completer completer = completer(command, engine);
    var reader = new ApprovalMenuLineReader(terminal);
    reader.setCompleter(completer);
    reader.setParser(parser());
    reader.setVariable(LineReader.HISTORY_SIZE, 1000);
    reader.setVariable(LineReader.HISTORY_FILE_SIZE, 1000);
    reader.setOpt(LineReader.Option.BRACKETED_PASTE);
    if (java.io.File.separatorChar == '\\') reader.setOpt(LineReader.Option.CASE_INSENSITIVE);
    return new Session(reader, completer);
  }

  public static Parser parser() {
    return new dev.mikoto2000.rei.ui.shell.ShellCompletionParser(
        java.nio.file.Path.of(System.getProperty("user.home")));
  }

  public static CompletionEngine completionEngine() {
    return new CompletionEngine()
        .register(new ChoiceCompletionProvider())
        .register(new FilePathCompletionProvider())
        .register(new PathFallbackCompletionProvider());
  }

  public static Completer completer(CommandLine command) {
    return completer(command, completionEngine());
  }

  public static Completer completer(CommandLine command, CompletionEngine engine) {
    return new JLineCompletionAdapter(
        command, engine, ProjectService::currentProjectOrStartupDirectory);
  }
}
