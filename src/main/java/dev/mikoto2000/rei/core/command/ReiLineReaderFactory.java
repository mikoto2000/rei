package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.completion.ApprovalMenuLineReader;
import dev.mikoto2000.rei.core.completion.ChoiceCompletionProvider;
import dev.mikoto2000.rei.core.completion.CompletionEngine;
import dev.mikoto2000.rei.core.completion.FilePathCompletionProvider;
import dev.mikoto2000.rei.core.completion.PathFallbackCompletionProvider;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.ui.shell.JLineCompletionAdapter;
import java.io.IOException;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.Parser;
import org.jline.terminal.Terminal;
import picocli.CommandLine;

/**
 * れい行読み取り元の情報を共通の形式で生成する。
 *
 * <p>Builds the canonical JLine input services for the Shell.
 */
public final class ReiLineReaderFactory {
  /**
   * 共通の実行基盤で受け渡す会話を表す。
   *
   * @param reader 読み取り元
   * @param completer 入力候補の提示先
   */
  public record Session(LineReader reader, Completer completer) {}

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ReiLineReaderFactory() {}

  /** 指定された内容から新しい対象を作成する。 */
  public static Session create(Terminal terminal, CommandLine command) throws IOException {
    return create(terminal, command, completionEngine());
  }

  /** 指定された内容から新しい対象を作成する。 */
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

  /** 共通の実行基盤で用いる入力の解釈を返す。 */
  public static Parser parser() {
    return new dev.mikoto2000.rei.ui.shell.ShellCompletionParser(
        java.nio.file.Path.of(System.getProperty("user.home")));
  }

  /** 共通の実行基盤で用いる入力補完エンジンを返す。 */
  public static CompletionEngine completionEngine() {
    return new CompletionEngine()
        .register(new ChoiceCompletionProvider())
        .register(new FilePathCompletionProvider())
        .register(new PathFallbackCompletionProvider());
  }

  /** 共通の実行基盤で用いる入力候補の提示先を返す。 */
  public static Completer completer(CommandLine command) {
    return completer(command, completionEngine());
  }

  /** 共通の実行基盤で用いる入力候補の提示先を返す。 */
  public static Completer completer(CommandLine command, CompletionEngine engine) {
    return new JLineCompletionAdapter(
        command, engine, ProjectService::currentProjectOrStartupDirectory);
  }
}
