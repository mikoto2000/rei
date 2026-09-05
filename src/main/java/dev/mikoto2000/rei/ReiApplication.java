

package dev.mikoto2000.rei;


import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
import org.jline.reader.Reference;
import org.jline.reader.SyntaxError;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.NonBlockingReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.mikoto2000.rei.core.command.ProjectAddDirectoryCompletion;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.core.command.ReiLineReaderFactory;
import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.command.UserInputService;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.ui.shell.JLineShellEventOutput;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventSession;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliJLineCompleter;

@EnableScheduling
@SpringBootApplication
public class ReiApplication {

  private final RootCommand rootCommand;
  private final CommandLine.IFactory factory;
  private final ModelHolderService currentModelHolder;
  private final EscCancellationMonitor escCancellationMonitor;
  private final CommandCancellationService commandCancellationService;
  private final CommandCompletionNotificationPolicy commandCompletionNotificationPolicy;
  private final CommandUserInputDisplayPolicy commandUserInputDisplayPolicy;
  private final AsyncVectorDocumentService asyncVectorDocumentService;
  private final SoundNotificationService soundNotificationService;
  private final ChatResponseNarrator chatResponseNarrator;
  private final AgentEventBus agentEventBus;
  private final AgentActivityTracker agentActivityTracker;
  private final Environment environment;

  private static final String COMMAND_COMPLETION_MESSAGE = "コマンド実行が完了しました";
  private static final String MULTILINE_CONTINUATION = "\\";
  private static final String MULTILINE_PROMPT = "...> ";
  private static final String PASTE_END_TOKEN = ".";
  private static final String PASTE_PROMPT = "paste> ";
  private static final DateTimeFormatter PROMPT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

  @Autowired
  public ReiApplication(RootCommand rootCommand, CommandLine.IFactory factory, ModelHolderService currentModelHolder,
      EscCancellationMonitor escCancellationMonitor, CommandCancellationService commandCancellationService,
      CommandCompletionNotificationPolicy commandCompletionNotificationPolicy,
      CommandUserInputDisplayPolicy commandUserInputDisplayPolicy,
      AsyncVectorDocumentService asyncVectorDocumentService, SoundNotificationService soundNotificationService,
      ChatResponseNarrator chatResponseNarrator, AgentEventBus agentEventBus,
      AgentActivityTracker agentActivityTracker, Environment environment) {
    this.rootCommand = rootCommand;
    this.factory = factory;
    this.currentModelHolder = currentModelHolder;
    this.escCancellationMonitor = escCancellationMonitor;
    this.commandCancellationService = commandCancellationService;
    this.commandCompletionNotificationPolicy = commandCompletionNotificationPolicy;
    this.commandUserInputDisplayPolicy = commandUserInputDisplayPolicy;
    this.asyncVectorDocumentService = asyncVectorDocumentService;
    this.soundNotificationService = soundNotificationService;
    this.chatResponseNarrator = chatResponseNarrator;
    this.agentEventBus = agentEventBus;
    this.agentActivityTracker = agentActivityTracker;
    this.environment = environment;
  }

  ReiApplication(RootCommand rootCommand, CommandLine.IFactory factory, ModelHolderService currentModelHolder,
      EscCancellationMonitor escCancellationMonitor, CommandCancellationService commandCancellationService,
      CommandCompletionNotificationPolicy commandCompletionNotificationPolicy,
      CommandUserInputDisplayPolicy commandUserInputDisplayPolicy,
      AsyncVectorDocumentService asyncVectorDocumentService, SoundNotificationService soundNotificationService,
      ChatResponseNarrator chatResponseNarrator, AgentEventBus agentEventBus) {
    this(rootCommand, factory, currentModelHolder, escCancellationMonitor, commandCancellationService,
        commandCompletionNotificationPolicy, commandUserInputDisplayPolicy, asyncVectorDocumentService,
        soundNotificationService, chatResponseNarrator, agentEventBus,
        new dev.mikoto2000.rei.topic.DefaultAgentActivityTracker(java.time.Clock.systemDefaultZone()), null);
  }

  public static void main(String[] args) throws IOException {
    SpringApplication application = new SpringApplication(ReiApplication.class);
    application.setDefaultProperties(ExternalConfigSupport.defaultProperties());
    ConfigurableApplicationContext context = application.run(args);
    int exitCode;
    try {
      var app = context.getBean(ReiApplication.class);
      launch(app, args);
    } finally {
      exitCode = SpringApplication.exit(context);
    }
    System.exit(exitCode);
  }

  static void launch(ReiApplication app, String[] args) throws IOException {
    app.run(args);
  }

  void run(String[] args) throws IOException {
    var cmd = new picocli.CommandLine(rootCommand, factory);
    var terminal = TerminalBuilder.builder()
      .system(true)
      .build();
    configureCommandOutput(cmd, terminal);

    ReiLineReaderFactory.Session inputSession = ReiLineReaderFactory.create(terminal, cmd);
    LineReader reader = inputSession.reader();
    configureMultilineKeyBinding(reader);
    ShellEventSession shellEvents = new ShellEventSession(agentEventBus,
        new ShellAgentEventRenderer(new JLineShellEventOutput(reader),
            ShellAgentEventRenderer.TopicNotificationOptions.from(environment)));

    System.out.println("AI Shell");
    System.out.println("通常入力は chat として扱います。/exit で終了します。");
    System.out.println("複数行入力: 複数行ペースト対応。行末に \\\\ を付けるか、Ctrl+J でも改行できます。");
    System.out.println("/paste で確実な複数行入力モード（終了は単独行の . ）");

    ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    UserInputService inputService = new UserInputService(new UserInputParser());
    try {
      shellLoop: while (true) {
        try {
          String line = reader.readLine(buildPrompt());
          if (line == null) {
            break;
          }
          agentActivityTracker.recordUserActivity(java.time.Instant.now());
          line = readPossiblyMultilineInput(line, reader);

          UserInputService.Input input = inputService.interpret(line);
          switch (input.kind()) {
            case EMPTY -> { }
            case EXIT -> {
              if (confirmExitIfNeeded(prompt -> reader.readLine(prompt))) {
                break shellLoop;
              }
            }
            case HELP -> {
              printUserInput(input.text(), terminal);
              executeInterruptibly(cmd, terminal, commandExecutor, "--help");
            }
            case VERSION -> {
              printUserInput(input.text(), terminal);
              executeInterruptibly(cmd, terminal, commandExecutor, "--version");
            }
            case PASTE -> {
              String pasted = readPasteBlock(reader);
              if (!pasted.isBlank()) {
                printUserInput(pasted, terminal);
                executeInterruptibly(cmd, terminal, commandExecutor, "chat", pasted);
              }
            }
            case COMMAND -> {
              String[] commandArgs = input.arguments();
              printUserInputIfNeeded(input.text(), terminal, commandArgs);
              if (isInteractiveShellCommand(commandArgs)) {
                executeInteractiveShellCommand(cmd, reader, terminal, inputSession.completer(), commandArgs);
              } else {
                executeInterruptibly(cmd, terminal, commandExecutor, commandArgs);
              }
            }
            case CHAT -> {
              printUserInput(input.text(), terminal);
              executeInterruptibly(cmd, terminal, commandExecutor, "chat", input.text());
            }
          }

        } catch (UserInterruptException e) {
          // Ctrl-C でその行だけキャンセル
        } catch (EndOfFileException e) {
          // Ctrl-D (EOF) は即時終了する
          break;
        }
      }
    } finally {
      commandExecutor.shutdownNow();
      shellEvents.close();
    }
  }

  static void configureCommandOutput(CommandLine cmd, Terminal terminal) {
    // Use JLine's encoding for command output as well as prompts and events.
    PrintWriter writer = new PrintWriter(terminal.writer(), true);
    cmd.setOut(writer);
    cmd.setErr(writer);
  }

  protected void executeInterruptibly(CommandLine cmd, Terminal terminal, ExecutorService commandExecutor, String... args)
      throws IOException {
    Attributes originalAttributes = terminal.enterRawMode();
    try {
      var future = commandExecutor.submit(() -> executeWithOutputPolicy(cmd, args));
      escCancellationMonitor.await(future, timeoutMillis -> terminal.reader().read(timeoutMillis), commandCancellationService::cancel);
    } finally {
      terminal.setAttributes(originalAttributes);
      if (!chatResponseNarrator.wasNarrated() && commandCompletionNotificationPolicy.shouldNotify(args)) {
        soundNotificationService.notify(COMMAND_COMPLETION_MESSAGE);
      }
      chatResponseNarrator.reset();
    }
  }

  int executeWithOutputPolicy(CommandLine command, String... args) {
    if (args == null || args.length == 0 || !"chat".equals(args[0])) {
      return command.execute(args);
    }
    PrintStream previousOut = System.out;
    PrintStream previousErr = System.err;
    try (PrintStream discarded = new PrintStream(java.io.OutputStream.nullOutputStream())) {
      System.setOut(discarded);
      System.setErr(discarded);
      return command.execute(args);
    } finally {
      System.setOut(previousOut);
      System.setErr(previousErr);
    }
  }

  boolean isInteractiveShellCommand(String... args) {
    return args != null && args.length > 0
        && "sh".equals(args[0]);
  }

  void executeInteractiveShellCommand(CommandLine cmd, LineReader reader, Terminal terminal, String... args) {
    executeInteractiveShellCommand(cmd, reader, terminal, ReiLineReaderFactory.completer(cmd), args);
  }

  void executeInteractiveShellCommand(CommandLine cmd, LineReader reader, Terminal terminal,
      Completer completer, String... args) {
    boolean paused = false;
    try {
      if (terminal.canPauseResume()) {
        terminal.pause(true);
        paused = true;
      }
      cmd.execute(args);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("システムシェルの起動待機が中断されました", e);
    } finally {
      if (paused) {
        terminal.resume();
      }
      clearPendingInputAfterInteractiveShell(reader, terminal);
    }
  }

  void clearPendingInputAfterInteractiveShell(LineReader reader, Terminal terminal) {
    reader.getBuffer().clear();
    try {
      while (true) {
        int read = terminal.reader().read(10);
        if (read == NonBlockingReader.READ_EXPIRED || read == NonBlockingReader.EOF) {
          break;
        }
      }
    } catch (IOException e) {
      // 次の readLine で自然に復旧できるため、残留入力の掃除失敗は無視する。
    }
  }

  String[] splitCommandLine(String line) {
    return new UserInputParser().split(line);
  }

  String buildPrompt() {
    return now().format(PROMPT_TIME_FORMATTER) + " " + currentModelHolder.get() + "> ";
  }

  LocalTime now() {
    return LocalTime.now();
  }

  String readPossiblyMultilineInput(String firstLine, LineReader reader) {
    if (!firstLine.endsWith(MULTILINE_CONTINUATION)) {
      return firstLine;
    }

    StringBuilder builder = new StringBuilder();
    String line = firstLine;
    while (line.endsWith(MULTILINE_CONTINUATION)) {
      builder.append(line, 0, line.length() - MULTILINE_CONTINUATION.length());
      builder.append(System.lineSeparator());
      line = reader.readLine(MULTILINE_PROMPT);
      if (line == null) {
        return builder.toString();
      }
    }
    builder.append(line);
    return builder.toString();
  }

  String readPasteBlock(LineReader reader) {
    StringBuilder builder = new StringBuilder();
    while (true) {
      String line = reader.readLine(PASTE_PROMPT);
      if (line == null || line.equals(PASTE_END_TOKEN)) {
        break;
      }
      if (!builder.isEmpty()) {
        builder.append(System.lineSeparator());
      }
      builder.append(line);
    }
    return builder.toString();
  }

  void configureMultilineKeyBinding(LineReader reader) {
    reader.getWidgets().put("insert-newline", () -> {
      reader.getBuffer().write('\n');
      return true;
    });
    Reference insertNewline = new Reference("insert-newline");
    if (reader.getKeyMaps().containsKey(LineReader.MAIN)) {
      reader.getKeyMaps().get(LineReader.MAIN).bind(insertNewline, KeyMap.ctrl('J'));
    }
    if (reader.getKeyMaps().containsKey(LineReader.EMACS)) {
      reader.getKeyMaps().get(LineReader.EMACS).bind(insertNewline, KeyMap.ctrl('J'));
    }
    if (reader.getKeyMaps().containsKey(LineReader.VIINS)) {
      reader.getKeyMaps().get(LineReader.VIINS).bind(insertNewline, KeyMap.ctrl('J'));
    }
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

  void printUserInputIfNeeded(String input, Terminal terminal, String... commandArgs) {
    if (commandUserInputDisplayPolicy.shouldDisplay(commandArgs)) {
      printUserInput(input, terminal);
    }
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

}
