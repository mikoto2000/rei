package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.completion.CompletionEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import picocli.CommandLine;

/** JLine入力補完を別の利用先へ接続する。 */
public final class JLineCompletionAdapter implements Completer {
  private final java.util.function.BiConsumer<ParsedLine, List<Candidate>> appendCompletions;

  /** 指定された値や利用先を使って、JLineCompletionAdapterを初期化する。 */
  public JLineCompletionAdapter(
      CommandLine command, CompletionEngine engine, Supplier<Path> directory) {
    var resolver = new CommandCompletionContextResolver();
    this.appendCompletions =
        (line, candidates) -> {
          for (var candidate : engine.complete(resolver.resolve(command, line, directory.get()))) {
            candidates.add(
                new Candidate(
                    candidate.value(),
                    candidate.display(),
                    candidate.kind(),
                    candidate.description(),
                    null,
                    null,
                    candidate.appendSpace()));
          }
        };
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    try {
      appendCompletions.accept(line, candidates);
    } catch (RuntimeException ignored) {
      /* Invalid input or metadata must never break the reader. */
    }
  }
}
