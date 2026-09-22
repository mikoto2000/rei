package dev.mikoto2000.rei.ui.shell;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.jline.reader.*;
import picocli.CommandLine;
import dev.mikoto2000.rei.core.completion.CompletionEngine;

public final class JLineCompletionAdapter implements Completer {
  private final CommandLine command;
  private final CompletionEngine engine;
  private final Supplier<Path> directory;
  private final CommandCompletionContextResolver resolver = new CommandCompletionContextResolver();
  public JLineCompletionAdapter(CommandLine command, CompletionEngine engine, Supplier<Path> directory) {
    this.command = command; this.engine = engine; this.directory = directory;
  }
  @Override public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    try {
      for (var candidate : engine.complete(resolver.resolve(command, line, directory.get()))) {
        candidates.add(new Candidate(candidate.value(), candidate.display(), candidate.kind(),
            candidate.description(), null, null, candidate.appendSpace()));
      }
    } catch (RuntimeException ignored) { /* Invalid input or metadata must never break the reader. */ }
  }
}
