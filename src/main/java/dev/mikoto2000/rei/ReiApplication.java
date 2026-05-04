

package dev.mikoto2000.rei;


import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.SyntaxError;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliJLineCompleter;

@EnableScheduling
@RequiredArgsConstructor
@SpringBootApplication
public class ReiApplication {

  private final RootCommand rootCommand;
  private final CommandLine.IFactory factory;
  private final ModelHolderService currentModelHolder;
  private final EscCancellationMonitor escCancellationMonitor;
  private final CommandCancellationService commandCancellationService;
  private final AsyncVectorDocumentService asyncVectorDocumentService;
  private final SoundNotificationService soundNotificationService;

  private final Path HISTORY_FILE = ReiPaths.historyFilePath();

  private static final String COMMAND_COMPLETION_MESSAGE = "コマンド実行が完了しました";

  public  static void main(String[] args) throws IOException {
    SpringApplication application = new SpringApplication(ReiApplication.class);
    application.setDefaultProperties(ExternalConfigSupport.defaultProperties());
    var context = application.run(args);
    var app = context.getBean(ReiApplication.class);
    app.run(args);
    context.close();
  }

  private void run(String[] args) throws IOException {
    var cmd = new picocli.CommandLine(rootCommand, factory);
    Completer completer = new SlashCommandCompleter(
        new PicocliJLineCompleter(cmd.getCommandSpec()),
        cmd.getSubcommands().keySet().stream().sorted().toList());
    try {
      ReiPaths.ensureParentDirectoryExists(HISTORY_FILE);
    } catch (Exception e) {
      throw new IOException("履歴ファイル用ディレクトリの作成に失敗しました: " + HISTORY_FILE, e);
    }

    var terminal = TerminalBuilder.builder()
      .system(true)
      .build();

    LineReader reader = LineReaderBuilder.builder()
      .terminal(terminal)
      .completer(completer)
      .variable(LineReader.HISTORY_FILE, HISTORY_FILE)
      .variable(LineReader.HISTORY_SIZE, 1000)
      .variable(LineReader.HISTORY_FILE_SIZE, 1000)
      .build();

    System.out.println("AI Shell");
    System.out.println("通常入力は chat として扱います。/exit で終了します。");

    ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    try {
      while (true) {
        try {
          String line = reader.readLine(currentModelHolder.get() + "> ");
          if (line == null) {
            break;
          }

          String trimmed = line.trim();
          if (trimmed.isEmpty()) {
            continue;
          }

          if (trimmed.equals("/exit") || trimmed.equals("/quit")) {
            if (confirmExitIfNeeded(prompt -> reader.readLine(prompt))) {
              break;
            }
            continue;
          }

          if (trimmed.equals("/help")) {
            printUserInput(trimmed, terminal);
            executeInterruptibly(cmd, terminal, commandExecutor, "--help");
            continue;
          }

          if (trimmed.equals("/version")) {
            printUserInput(trimmed, terminal);
            executeInterruptibly(cmd, terminal, commandExecutor, "--version");
            continue;
          }

          if (trimmed.startsWith("/")) {
            String commandText = trimmed.substring(1).trim();
            if (commandText.isEmpty()) {
              continue;
            }
            printUserInput(trimmed, terminal);
            executeInterruptibly(cmd, terminal, commandExecutor, splitCommandLine(commandText));
          } else {
            printUserInput(trimmed, terminal);
            executeInterruptibly(cmd, terminal, commandExecutor, "chat", trimmed);
          }

        } catch (UserInterruptException e) {
          // Ctrl-C でその行だけキャンセル
        } catch (EndOfFileException e) {
          if (confirmExitIfNeeded(prompt -> reader.readLine(prompt))) {
            break;
          }
        }
      }
    } finally {
      commandExecutor.shutdownNow();
    }
  }

  protected void executeInterruptibly(CommandLine cmd, Terminal terminal, ExecutorService commandExecutor, String... args)
      throws IOException {
    Attributes originalAttributes = terminal.enterRawMode();
    try {
      var future = commandExecutor.submit(() -> cmd.execute(args));
      escCancellationMonitor.await(future, timeoutMillis -> terminal.reader().read(timeoutMillis), commandCancellationService::cancel);
    } finally {
      terminal.setAttributes(originalAttributes);
      soundNotificationService.notify(COMMAND_COMPLETION_MESSAGE);
    }
  }

  private String[] splitCommandLine(String line) {
    return line.split("\\s+");
  }

  void printUserInput(String input) {
    System.out.print(formatUserInput(input));
  }

  void printUserInput(String input, Terminal terminal) {
    AttributedStringBuilder builder = new AttributedStringBuilder();
    AttributedStyle style = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);

    builder.append(System.lineSeparator());
    builder.append("┌ User", style);
    builder.append(System.lineSeparator());
    for (String line : input.split("\\R", -1)) {
      builder.append(line, style);
      builder.append(System.lineSeparator());
    }
    builder.append("└", style);
    builder.append(System.lineSeparator());
    builder.append(System.lineSeparator());

    terminal.writer().print(builder.toAnsi(terminal));
    terminal.writer().flush();
  }

  String formatUserInput(String input) {
    StringBuilder builder = new StringBuilder();
    builder.append(System.lineSeparator());
    builder.append("┌ User").append(System.lineSeparator());
    for (String line : input.split("\\R", -1)) {
      builder.append(line).append(System.lineSeparator());
    }
    builder.append("└").append(System.lineSeparator());
    builder.append(System.lineSeparator());
    return builder.toString();
  }

  boolean confirmExitIfNeeded(ConfirmationReader confirmationReader) {
    if (!asyncVectorDocumentService.hasActiveEmbeddings()) {
      return true;
    }
    System.out.println("警告: embed add の処理が実行中です。");
    try {
      String answer = confirmationReader.read("終了しますか? [y/N] ");
      if (answer != null) {
        String normalized = answer.trim().toLowerCase();
        if (normalized.equals("y") || normalized.equals("yes")) {
          return true;
        }
      }
    } catch (UserInterruptException | EndOfFileException e) {
      // 終了確認自体が中断された場合は終了を取り消す
    }
    System.out.println("終了をキャンセルしました。");
    return false;
  }

  @FunctionalInterface
  interface ConfirmationReader {
    String read(String prompt) throws UserInterruptException, EndOfFileException;
  }

  private static final class SlashCommandCompleter implements Completer {

    private static final List<String> BUILTIN_COMMANDS = List.of("/exit", "/quit", "/help", "/version");

    private final Completer delegate;
    private final List<String> rootCommands;
    private final Parser parser = new DefaultParser();

    private SlashCommandCompleter(Completer delegate, List<String> rootCommands) {
      this.delegate = delegate;
      this.rootCommands = rootCommands;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
      String rawLine = line.line();
      if (rawLine == null || !rawLine.startsWith("/")) {
        return;
      }

      if (isCompletingRootCommand(rawLine)) {
        completeRootCommand(rawLine, candidates);
        return;
      }

      try {
        delegate.complete(reader, stripSlash(line), candidates);
      } catch (SyntaxError e) {
        return;
      }
    }

    private boolean isCompletingRootCommand(String rawLine) {
      return !rawLine.substring(1).contains(" ");
    }

    private void completeRootCommand(String current, List<Candidate> candidates) {
      for (String builtinCommand : BUILTIN_COMMANDS) {
        if (builtinCommand.startsWith(current)) {
          candidates.add(new Candidate(builtinCommand));
        }
      }
      for (String rootCommand : rootCommands) {
        String slashCommand = "/" + rootCommand;
        if (slashCommand.startsWith(current)) {
          candidates.add(new Candidate(slashCommand));
        }
      }
    }

    private ParsedLine stripSlash(ParsedLine line) throws SyntaxError {
      String rawLine = line.line();
      String strippedLine = rawLine.length() <= 1 ? "" : rawLine.substring(1);
      int strippedCursor = Math.max(0, line.cursor() - 1);
      return parser.parse(strippedLine, strippedCursor, Parser.ParseContext.COMPLETE);
    }
  }
}
