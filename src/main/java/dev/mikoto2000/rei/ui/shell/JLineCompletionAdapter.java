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
  /** コマンド。 */
  private final CommandLine command;

  /** 入力候補を選ぶ共通の処理。 */
  private final CompletionEngine engine;

  /** ディレクトリ。 */
  private final Supplier<Path> directory;

  /** 対象の解決。 */
  private final CommandCompletionContextResolver resolver = new CommandCompletionContextResolver();

  /** 指定された値や利用先を使って、JLineCompletionAdapterを初期化する。 */
  public JLineCompletionAdapter(
      CommandLine command, CompletionEngine engine, Supplier<Path> directory) {
    this.command = command;
    this.engine = engine;
    this.directory = directory;
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    try {
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
    } catch (RuntimeException ignored) {
      /* Invalid input or metadata must never break the reader. */
    }
  }
}
