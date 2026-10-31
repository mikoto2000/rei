package dev.mikoto2000.rei;

import dev.mikoto2000.rei.core.command.ReiLineReaderFactory;
import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.command.UserInputService;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.CommandCompletionNotificationPolicy;
import dev.mikoto2000.rei.core.service.CommandUserInputDisplayPolicy;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.ui.shell.JLineShellEventOutput;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventSession;
import dev.mikoto2000.rei.ui.shell.UserInputFrame;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jline.keymap.KeyMap;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import picocli.CommandLine;

/** 外部設定を準備してアプリケーションを起動し、ターミナルでの対話を受け付ける。 */
@EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ReiApplication {
  /** 承認要求一覧。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvalRequests;

  /** 承認継続入力一覧。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.core.policy.ApprovalContinuationService approvalContinuations;

  /** 承認実行一覧。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.application.run.RunRegistry approvalRuns;

  /** 完了条件Engine。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private dev.mikoto2000.rei.core.completion.CompletionEngine completionEngine =
      ReiLineReaderFactory.completionEngine();

  /** 音声入力からの依頼かどうか。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.voice.VoiceInputCoordinator voiceInput;

  /** 音声イベント一覧。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.voice.VoiceEventPublisher voiceEvents;

  /** 音声配信。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.voice.VoiceDeliveryService voiceDelivery;

  /** 作業ルートコマンド。 */
  private final RootCommand rootCommand;

  /** 生成元。 */
  private final CommandLine.IFactory factory;

  /** 現在モデル保持先。 */
  private final ModelHolderService currentModelHolder;

  /** escキャンセル排他制御。 */
  private final EscCancellationMonitor escCancellationMonitor;

  /** コマンドキャンセルサービス。 */
  private final CommandCancellationService commandCancellationService;

  /** コマンド完了条件通知判断基準。 */
  private final CommandCompletionNotificationPolicy commandCompletionNotificationPolicy;

  /** コマンド利用者入力表示判断基準。 */
  private final CommandUserInputDisplayPolicy commandUserInputDisplayPolicy;

  /** 非同期ベクトル文書サービス。 */
  private final AsyncVectorDocumentService asyncVectorDocumentService;

  /** 音通知通知サービス。 */
  private final SoundNotificationService soundNotificationService;

  /** チャット応答読み上げの案内。 */
  private final ChatResponseNarrator chatResponseNarrator;

  /** エージェント実行状況を共有する通知先。 */
  private final AgentEventBus agentEventBus;

  /** エージェント作業状況追跡。 */
  private final AgentActivityTracker agentActivityTracker;

  /** 実行環境。 */
  private final Environment environment;

  /** プロジェクトシェル作業状況。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.ui.shell.ProjectShellActivity projectShellActivity;

  /** 活動中実行一覧。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.core.chat.ConversationInputRouter activeRuns;

  /** 活動中実行表示。 */
  @Autowired(required = false)
  private dev.mikoto2000.rei.ui.shell.ActiveRunDisplay activeRunDisplay;

  /** プロジェクト一覧。 */
  @Autowired private ProjectService projects;

  /** 終了Notifier。 */
  @Autowired private ApplicationShutdownNotifier shutdownNotifier;

  /** コマンド完了条件メッセージ。 */
  private static final String COMMAND_COMPLETION_MESSAGE = "コマンド実行が完了しました";

  /** MULTILINE継続入力。 */
  private static final String MULTILINE_CONTINUATION = "\\";

  /** MULTILINEプロンプト。 */
  private static final String MULTILINE_PROMPT = "...> ";

  /** PASTE終了トークン。 */
  private static final String PASTE_END_TOKEN = ".";

  /** PASTEプロンプト。 */
  private static final String PASTE_PROMPT = "paste> ";

  /** プロンプト時刻表示形式への変換。 */
  private static final DateTimeFormatter PROMPT_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm");

  /** 指定された値や利用先を使って、ReiApplicationを初期化する。 */
  @Autowired
  public ReiApplication(
      RootCommand rootCommand,
      CommandLine.IFactory factory,
      ModelHolderService currentModelHolder,
      EscCancellationMonitor escCancellationMonitor,
      CommandCancellationService commandCancellationService,
      CommandCompletionNotificationPolicy commandCompletionNotificationPolicy,
      CommandUserInputDisplayPolicy commandUserInputDisplayPolicy,
      AsyncVectorDocumentService asyncVectorDocumentService,
      SoundNotificationService soundNotificationService,
      ChatResponseNarrator chatResponseNarrator,
      AgentEventBus agentEventBus,
      AgentActivityTracker agentActivityTracker,
      Environment environment) {
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

  /** れいの起動を利用するため、指定を補って共通の初期化へ引き渡す。 */
  ReiApplication(
      RootCommand rootCommand,
      CommandLine.IFactory factory,
      ModelHolderService currentModelHolder,
      EscCancellationMonitor escCancellationMonitor,
      CommandCancellationService commandCancellationService,
      CommandCompletionNotificationPolicy commandCompletionNotificationPolicy,
      CommandUserInputDisplayPolicy commandUserInputDisplayPolicy,
      AsyncVectorDocumentService asyncVectorDocumentService,
      SoundNotificationService soundNotificationService,
      ChatResponseNarrator chatResponseNarrator,
      AgentEventBus agentEventBus) {
    this(
        rootCommand,
        factory,
        currentModelHolder,
        escCancellationMonitor,
        commandCancellationService,
        commandCompletionNotificationPolicy,
        commandUserInputDisplayPolicy,
        asyncVectorDocumentService,
        soundNotificationService,
        chatResponseNarrator,
        agentEventBus,
        new dev.mikoto2000.rei.topic.DefaultAgentActivityTracker(
            java.time.Clock.systemDefaultZone()),
        null);
  }

  /** れいの起動の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] args) throws IOException {
    dev.mikoto2000.rei.launcher.BackendMode mode;
    try {
      mode = dev.mikoto2000.rei.launcher.BackendMode.parse(args);
    } catch (IllegalArgumentException error) {
      System.err.println("Error: " + error.getMessage());
      System.exit(2);
      return;
    }
    try {
      var options =
          StartupOptions.parse(
              args, dev.mikoto2000.rei.core.datasource.ReiPaths.startupDirectory());
      if (options.printHelpIfRequested()) return;
    } catch (CommandLine.ParameterException error) {
      System.err.println("Error: " + error.getMessage());
      System.exit(error.getCommandLine().getCommandSpec().exitCodeOnInvalidInput());
      return;
    }
    System.setProperty(
        "rei.data-dir", dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().toString());
    if (mode == dev.mikoto2000.rei.launcher.BackendMode.SERVER
        && (System.getenv("REI_API_KEY") == null || System.getenv("REI_API_KEY").isBlank())) {
      System.err.println(
          "Server mode requires REI_API_KEY. Configure the existing API key and restart; no credentials have been generated.");
      System.exit(2);
      return;
    }
    SpringApplication application = new SpringApplication(ReiApplication.class);
    boolean backend =
        StartupOptions.parse(args, dev.mikoto2000.rei.core.datasource.ReiPaths.startupDirectory())
            .backend();
    application.addInitializers(
        c ->
            c.getEnvironment()
                .getPropertySources()
                .addFirst(
                    new org.springframework.core.env.MapPropertySource(
                        "reiBackendMode", java.util.Map.of("rei.backend-mode", backend))));
    application.setDefaultProperties(ExternalConfigSupport.defaultProperties());
    dev.mikoto2000.rei.web.WebApplication.configure(application, System.getenv("REI_API_KEY"));
    ConfigurableApplicationContext context = application.run(args);
    boolean consoleExit =
        context.getEnvironment().getProperty(ForegroundConsole.PROPERTY, Boolean.class, false);
    if (mode == dev.mikoto2000.rei.launcher.BackendMode.SERVER && !consoleExit) return;
    int exitCode;
    try {
      var app = context.getBean(ReiApplication.class);
      if (backend && consoleExit) {
        ForegroundConsole.run(context);
      } else if (backend) {
        var stopped = new java.util.concurrent.CountDownLatch(1);
        context.addApplicationListener(
            (org.springframework.context.ApplicationListener<
                    org.springframework.context.event.ContextClosedEvent>)
                e -> stopped.countDown());
        try {
          stopped.await();
        } catch (InterruptedException interrupted) {
          // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
          Thread.currentThread().interrupt();
        }
      } else launch(app, args);
    } finally {
      exitCode = SpringApplication.exit(context);
    }
    System.exit(exitCode);
  }

  /** れいの起動に関する起動を処理する。 */
  static void launch(ReiApplication app, String[] args) throws IOException {
    app.run(args);
  }

  /** れいの起動の操作を実行する。 */
  void run(String[] args) throws IOException {
    var options = StartupOptions.parse(args, projects.startupDirectory());
    if (options.printHelpIfRequested()) return;
    var client = projects.newClient();
    try (var scope = client.open();
        var notifications = projects.notificationsFollow(client)) {
      if (options.project() != null) projects.cd(options.project().toString());
      runShell(args, client);
    }
  }

  /** 依頼された操作を進めるため、シェルを実行する。 */
  void runShell(String[] args, dev.mikoto2000.rei.core.project.ProjectClient client)
      throws IOException {
    var cmd = new picocli.CommandLine(rootCommand, factory);
    rootCommand.configureCommands(cmd);
    var terminal = terminalBuilder(System.out).build();
    configureCommandOutput(cmd, terminal);
    dev.mikoto2000.rei.ui.shell.ShellProjectCommands.configure(
        cmd, projects, client, projectShellActivity);

    ReiLineReaderFactory.Session inputSession =
        ReiLineReaderFactory.create(terminal, cmd, completionEngine);
    LineReader reader = inputSession.reader();
    var llm = cmd.getSubcommands().get("llm");
    if (llm != null
        && llm.getCommand() instanceof dev.mikoto2000.rei.llm.capture.LlmCaptureCommand capture)
      capture.setConfirmation(
          prompt -> {
            try {
              var answer = reader.readLine(prompt);
              return "y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer);
            } catch (org.jline.reader.EndOfFileException
                | org.jline.reader.UserInterruptException declined) {
              return false;
            }
          });
    configureMultilineKeyBinding(reader);
    var eventOutput = new JLineShellEventOutput(reader);
    var approvalMenu =
        new dev.mikoto2000.rei.core.completion.ApprovalActionMenu(
            reader,
            () -> {
              try (var scope = client.open()) {
                return new dev.mikoto2000.rei.core.completion.ApprovalActionMenu.Owner(
                    projects.currentContext().id(), projects.currentSessionId());
              }
            });
    eventOutput.setApprovalMenu(approvalMenu);
    if (approvalRequests != null)
      approvalMenu.follow(
          owner -> {
            if (owner.project() == null || owner.session() == null) return java.util.List.of();
            return approvalRequests.list(owner.project()).stream()
                .filter(request -> owner.session().equals(request.sessionId()))
                .filter(request -> approvalRunActive(request.runId()))
                .filter(
                    request ->
                        dev.mikoto2000.rei.core.completion.ApprovalDisplayState.pending(
                            request.status(),
                            request.expiresAt(),
                            approvalContinuations == null
                                ? "UNAVAILABLE"
                                : approvalContinuations.state(request.id()).orElse("UNAVAILABLE"),
                            java.time.Instant.now()))
                .map(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.Request::id)
                .toList();
          });
    var notificationOptions = ShellAgentEventRenderer.TopicNotificationOptions.from(environment);
    dev.mikoto2000.rei.event.AgentEventListener shellListener;
    if (projectShellActivity != null) {
      projectShellActivity.attach(eventOutput, notificationOptions);
      shellListener = projectShellActivity;
    } else {
      shellListener = new ShellAgentEventRenderer(eventOutput, notificationOptions);
    }
    ShellEventSession shellEvents = new ShellEventSession(agentEventBus, shellListener);
    var voiceSubscription =
        voiceEvents == null
            ? null
            : voiceEvents.subscribe(
                new dev.mikoto2000.rei.voice.VoiceShellEventRenderer(eventOutput));
    var voiceSubmittedSubscription =
        voiceDelivery == null
            ? null
            : voiceDelivery.onSubmitted(
                input -> eventOutput.printBlock(UserInputFrame.format(input.text(), now())));

    System.out.println("AI Shell");
    System.out.println("通常入力は chat として扱います。/exit で終了します。");
    System.out.println("複数行入力: 複数行ペースト対応。通常入力では行末に \\ を付けるか、Ctrl+J でも改行できます。");
    System.out.println("/paste で確実な複数行入力モード（終了は単独行の . ）");

    ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    UserInputService inputService = new UserInputService(new UserInputParser());
    try (var commandHistory =
            new dev.mikoto2000.rei.ui.shell.ProjectCommandHistory(
                () -> (org.jline.reader.impl.LineReaderImpl) reader,
                projects::currentContext,
                dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current());
        var consoleSession = new dev.mikoto2000.rei.ui.shell.AgentConsoleSession();
        var runPrompt =
            new dev.mikoto2000.rei.ui.shell.ActiveRunPrompt(
                reader,
                activeRuns,
                () -> {
                  try (var scope = client.open()) {
                    return buildPrompt();
                  }
                },
                activeRunDisplay)) {
      startVoiceAutomatically(
          cmd,
          terminal,
          line -> eventOutput.printBlock(new org.jline.utils.AttributedString(line)));
      shellLoop:
      while (true) {
        try {
          commandHistory.refresh();
          String line;
          approvalMenu.enabled(true);
          try {
            line = runPrompt.readLine();
          } finally {
            approvalMenu.enabled(false);
          }
          if (line == null) {
            break;
          }
          agentActivityTracker.recordUserActivity(java.time.Instant.now());
          line = readPossiblyMultilineInput(line, reader);

          UserInputService.Input input = inputService.interpret(line);
          switch (input.kind()) {
            case EMPTY -> {}
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
                executeInteractiveShellCommand(
                    cmd, reader, terminal, inputSession.completer(), commandArgs);
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
          if (environment != null
              && environment.getProperty(ForegroundConsole.PROPERTY, Boolean.class, false)) break;
          // Ctrl-C でその行だけキャンセル
        } catch (EndOfFileException e) {
          // Ctrl-D (EOF) は即時終了する
          break;
        }
      }
    } finally {
      shutdownNotifier.begin("shell_exit");
      approvalMenu.close();
      commandExecutor.shutdownNow();
      var voiceCommand = cmd.getSubcommands().get("voice");
      if (voiceCommand != null
          && voiceCommand.getCommand() instanceof dev.mikoto2000.rei.voice.VoiceCommand voice)
        voice.closeAutoStart();
      if (voiceInput != null) voiceInput.close();
      if (voiceSubmittedSubscription != null) voiceSubmittedSubscription.close();
      if (voiceSubscription != null) voiceSubscription.close();
      shellEvents.close();
    }
  }

  /** 要求された処理を利用できるよう、音声Automaticallyを開始する。 */
  static void startVoiceAutomatically(
      CommandLine cmd, Terminal terminal, java.util.function.Consumer<String> output) {
    var command = cmd.getSubcommands().get("voice");
    if (command != null
        && command.getCommand() instanceof dev.mikoto2000.rei.voice.VoiceCommand voice)
      voice.autoStart(isInteractiveTerminal(terminal), output);
  }

  /** れいの起動において、承認実行活動中に該当するか判定する。 */
  private boolean approvalRunActive(String runId) {
    if (approvalRuns == null) return true;
    try {
      return !approvalRuns.get(runId).status().isTerminal();
    } catch (dev.mikoto2000.rei.application.run.RunNotFoundException missing) {
      return false;
    }
  }

  /** れいの起動において、対話終端かどうかを判定する。 */
  static boolean isInteractiveTerminal(Terminal terminal) {
    if (terminal == null
        || terminal.getType() == null
        || Terminal.TYPE_DUMB.equals(terminal.getType())
        || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType())) return false;
    // Terminal type can be forced even with piped stdin. Require actual input-TTY evidence.
    try {
      return terminal instanceof org.jline.terminal.spi.TerminalExt ext
          && ext.getSystemStream() != null
          && ext.getProvider() != null
          && ext.getProvider().isSystemStream(org.jline.terminal.spi.SystemStream.Input);
    } catch (RuntimeException | LinkageError unavailable) {
      return false;
    }
  }

  /** れいの起動で用いる終端組み立てを返す。 */
  static TerminalBuilder terminalBuilder(PrintStream output) {
    var builder = TerminalBuilder.builder().system(true);
    // JLine defaults to UTF-8, including its dumb-terminal fallback. On Windows
    // the console may still use CP932; use the JVM's actual stdout encoding.
    // Preserve explicit JLine encoding/codepage overrides.
    if (System.getProperty(TerminalBuilder.PROP_ENCODING) == null
        && System.getProperty(TerminalBuilder.PROP_CODEPAGE) == null) {
      builder.encoding(output.charset());
    }
    return builder;
  }

  /** れいの起動に関する設定コマンド出力を処理する。 */
  static void configureCommandOutput(CommandLine cmd, Terminal terminal) {
    // RunLast extends picocli's legacy AbstractHandler, which captures System.out/err.
    // After AgentConsoleSession replaces those streams, execute() would treat the
    // captured streams as overrides and replace our terminal writers. Delegate via
    // IExecutionStrategy so the shell keeps its explicitly configured writers.
    var executionStrategy = cmd.getExecutionStrategy();
    cmd.setExecutionStrategy(executionStrategy::execute);
    // Use JLine's encoding for command output as well as prompts and events.
    PrintWriter writer = new PrintWriter(terminal.writer(), true);
    cmd.setOut(writer);
    cmd.setErr(writer);
    var chatgpt = cmd.getSubcommands().get("chatgpt");
    if (chatgpt != null
        && chatgpt.getCommand() instanceof dev.mikoto2000.rei.chatgpt.ChatGptCommand command) {
      command.setShellOutput(writer);
    }
    var subagent = cmd.getSubcommands().get("subagent");
    if (subagent != null
        && subagent.getCommand() instanceof dev.mikoto2000.rei.subagent.SubAgentCommand command) {
      command.setShellOutput(writer);
    }
    var llm = cmd.getSubcommands().get("llm");
    if (llm != null
        && llm.getCommand() instanceof dev.mikoto2000.rei.llm.capture.LlmCaptureCommand command)
      command.setShellOutput(writer);
    var runs = cmd.getSubcommands().get("runs");
    if (runs != null
        && runs.getCommand() instanceof dev.mikoto2000.rei.ui.shell.RunsCommand command) {
      command.setShellOutput(writer);
    }
    var history = cmd.getSubcommands().get("history");
    var sleep = cmd.getSubcommands().get("sleep");
    var work = cmd.getSubcommands().get("work");
    var checkpoint = cmd.getSubcommands().get("checkpoint");
    if (checkpoint != null
        && checkpoint.getCommand()
            instanceof dev.mikoto2000.rei.checkpoint.CheckpointCommand command)
      command.setShellOutput(writer);
    var resume = cmd.getSubcommands().get("resume");
    if (resume != null
        && resume.getCommand() instanceof dev.mikoto2000.rei.checkpoint.ResumeCommand command)
      command.setShellOutput(writer);
    var approval = cmd.getSubcommands().get("approval");
    var timer = cmd.getSubcommands().get("timer");
    var dependency = cmd.getSubcommands().get("dependency");
    if (dependency != null
        && dependency.getCommand()
            instanceof dev.mikoto2000.rei.core.dependency.DependencyCommand command)
      command.setShellOutput(writer);
    var attention = cmd.getSubcommands().get("attention");
    var goal = cmd.getSubcommands().get("goal");
    var reflection = cmd.getSubcommands().get("reflection");
    if (reflection != null
        && reflection.getCommand()
            instanceof dev.mikoto2000.rei.reflection.ReflectionCommand command)
      command.setShellOutput(writer);
    if (goal != null && goal.getCommand() instanceof dev.mikoto2000.rei.goal.GoalCommand command)
      command.setShellOutput(writer);
    if (attention != null
        && attention.getCommand() instanceof dev.mikoto2000.rei.attention.AttentionCommand command)
      command.setShellOutput(writer);
    if (timer != null
        && timer.getCommand() instanceof dev.mikoto2000.rei.temporal.TimerCommand command)
      command.setShellOutput(writer);
    if (approval != null
        && approval.getCommand() instanceof dev.mikoto2000.rei.core.policy.ApprovalCommand command)
      command.setShellOutput(writer);
    if (work != null
        && work.getCommand() instanceof dev.mikoto2000.rei.workcontext.WorkCommand command)
      command.setShellOutput(writer);
    if (sleep != null
        && sleep.getCommand() instanceof dev.mikoto2000.rei.memory.command.SleepCommand command)
      command.setShellOutput(writer);
    if (history != null
        && history.getCommand() instanceof dev.mikoto2000.rei.ui.shell.HistoryCommand command) {
      command.setShellOutput(writer);
    }
    var dot = cmd.getSubcommands().get("dot");
    if (dot != null && dot.getCommand() instanceof dev.mikoto2000.rei.dot.DotCommand command)
      command.setShellOutput(writer);
    var summarize = cmd.getSubcommands().get("summarize");
    if (summarize != null
        && summarize.getCommand()
            instanceof dev.mikoto2000.rei.summarize.command.SummarizeCommand command) {
      command.setShellOutput(writer);
    }
  }

  /** 依頼された操作を進めるため、Interruptiblyを実行する。 */
  protected void executeInterruptibly(
      CommandLine cmd, Terminal terminal, ExecutorService commandExecutor, String... args)
      throws IOException {
    if (args.length > 0
        && ("llm".equals(args[0])
            || "cancel".equals(args[0])
            || "agent".equals(args[0])
            || "history".equals(args[0])
            || "summarize".equals(args[0])
            || "image".equals(args[0])
            || ("chat".equals(args[0])
                && cmd.getSubcommands().get("chat") != null
                && cmd.getSubcommands().get("chat").getCommand()
                    instanceof dev.mikoto2000.rei.ui.shell.ChatCommand chat
                && chat.acceptsAsynchronously()))) {
      cmd.execute(args);
      return;
    }
    Attributes originalAttributes = terminal.enterRawMode();
    try {
      var future = commandExecutor.submit(() -> executeWithOutputPolicy(cmd, args));
      try {
        boolean consoleExit =
            environment != null
                && environment.getProperty(ForegroundConsole.PROPERTY, Boolean.class, false);
        escCancellationMonitor.await(
            future,
            timeoutMillis ->
                consoleExit
                    ? ForegroundConsole.readCommandInput(terminal.reader(), timeoutMillis)
                    : terminal.reader().read(timeoutMillis),
            () -> {
              boolean accepted = commandCancellationService.cancel();
              cmd.getErr()
                  .println(
                      accepted
                          ? "[cancel] Esc により停止を要求しました。"
                          : "[cancel] 停止対象の実行がないか、既に停止を要求済みです。");
              cmd.getErr().flush();
            });
      } catch (UserInterruptException | EndOfFileException exit) {
        commandCancellationService.cancel();
        future.cancel(true);
        throw exit;
      }
    } finally {
      terminal.setAttributes(originalAttributes);
      if (!chatResponseNarrator.wasNarrated()
          && commandCompletionNotificationPolicy.shouldNotify(args)) {
        soundNotificationService.notify(COMMAND_COMPLETION_MESSAGE);
      }
      chatResponseNarrator.reset();
    }
  }

  /** 依頼された操作を進めるため、付き出力判断基準を実行する。 */
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

  /** れいの起動において、対話シェルコマンドかどうかを判定する。 */
  boolean isInteractiveShellCommand(String... args) {
    return args != null && args.length > 0 && "sh".equals(args[0]);
  }

  /** 依頼された操作を進めるため、対話シェルコマンドを実行する。 */
  void executeInteractiveShellCommand(
      CommandLine cmd, LineReader reader, Terminal terminal, String... args) {
    executeInteractiveShellCommand(
        cmd, reader, terminal, ReiLineReaderFactory.completer(cmd, completionEngine), args);
  }

  /** 依頼された操作を進めるため、対話シェルコマンドを実行する。 */
  void executeInteractiveShellCommand(
      CommandLine cmd, LineReader reader, Terminal terminal, Completer completer, String... args) {
    boolean paused = false;
    try {
      if (terminal.canPauseResume()) {
        terminal.pause(true);
        paused = true;
      }
      cmd.execute(args);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new IllegalStateException("システムシェルの起動待機が中断されました", e);
    } finally {
      if (paused) {
        terminal.resume();
      }
      clearPendingInputAfterInteractiveShell(reader, terminal);
    }
  }

  /** 保持している情報を初期状態へ戻すため、待機中入力変更後対話シェルを消去する。 */
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

  /** れいの起動で用いる分割コマンド行を返す。 */
  String[] splitCommandLine(String line) {
    return new UserInputParser().split(line);
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
  String buildPrompt() {
    var progress =
        activeRunDisplay == null ? java.util.List.<String>of() : activeRunDisplay.progressRows();
    return (progress.isEmpty() ? "" : String.join("\n", progress).replace("%", "%%") + "\n")
        + (activeRunDisplay == null ? "" : activeRunDisplay.promptStatus() + "\n")
        + now().format(PROMPT_TIME_FORMATTER)
        + " "
        + currentModelHolder.get()
        + "> ";
  }

  /** れいの起動で用いる現在日時を返す。 */
  LocalTime now() {
    return LocalTime.now();
  }

  /** 必要な内容を参照できるよう、PossiblyMultiline入力を読み取る。 */
  String readPossiblyMultilineInput(String firstLine, LineReader reader) {
    // Slash-command arguments use literal backslashes, including the trailing
    // separator inserted by directory completion. Continuation is for chat input.
    if (firstLine.stripLeading().startsWith("/") || !firstLine.endsWith(MULTILINE_CONTINUATION)) {
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

  /** 必要な内容を参照できるよう、Pasteまとまりを読み取る。 */
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

  /** れいの起動に関する設定Multilineキー関連付けを処理する。 */
  void configureMultilineKeyBinding(LineReader reader) {
    var terminal = reader.getTerminal();
    if (terminal != null
        && (Terminal.TYPE_DUMB.equals(terminal.getType())
            || Terminal.TYPE_DUMB_COLOR.equals(terminal.getType()))) return;
    reader
        .getWidgets()
        .put(
            "insert-newline",
            () -> {
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

  /** 利用者に結果を伝えるため、利用者入力を出力する。 */
  void printUserInput(String input) {
    System.out.print(formatUserInput(input));
  }

  /** 利用者に結果を伝えるため、利用者入力を出力する。 */
  void printUserInput(String input, Terminal terminal) {
    terminal.writer().print(UserInputFrame.format(input, now()).toAnsi(terminal));
    terminal.writer().flush();
  }

  /** 利用者に結果を伝えるため、利用者入力条件付きNeededを出力する。 */
  void printUserInputIfNeeded(String input, Terminal terminal, String... commandArgs) {
    if (commandUserInputDisplayPolicy.shouldDisplay(commandArgs)) {
      printUserInput(input, terminal);
    }
  }

  /** 内容を表示・参照しやすくするため、利用者入力を整形する。 */
  String formatUserInput(String input) {
    return UserInputFrame.format(input, now()).toString();
  }

  /** れいの起動において、confirm終了条件付きNeededに該当するか判定する。 */
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

  /** れいの起動に必要な確認読み取り元を扱う。 */
  @FunctionalInterface
  interface ConfirmationReader {
    /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
    String read(String prompt) throws UserInterruptException, EndOfFileException;
  }
}
