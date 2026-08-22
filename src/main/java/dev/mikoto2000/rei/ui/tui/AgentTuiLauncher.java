package dev.mikoto2000.rei.ui.tui;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.command.ChatCommand;
import dev.mikoto2000.rei.core.command.RootCommand;
import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.command.UserInputService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.ui.projection.DefaultAgentUiProjection;
import dev.tamboui.backend.jline3.JLineBackend;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;
import picocli.CommandLine;

/** Shared TUI lifecycle used by both the shell command and startup mode. */
@Component
public final class AgentTuiLauncher {
  private final AgentEventBus eventBus;
  private final ChatCommand chatCommand;
  private final CommandCancellationService cancellationService;
  private final ObjectProvider<RootCommand> rootCommand;
  private final CommandLine.IFactory commandFactory;

  public AgentTuiLauncher(AgentEventBus eventBus, ChatCommand chatCommand,
      CommandCancellationService cancellationService, ObjectProvider<RootCommand> rootCommand,
      CommandLine.IFactory commandFactory) {
    this.eventBus = eventBus;
    this.chatCommand = chatCommand;
    this.cancellationService = cancellationService;
    this.rootCommand = rootCommand;
    this.commandFactory = commandFactory;
  }

  public void run(org.jline.terminal.Terminal shellTerminal) {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    AgentEventBus.Subscription subscription = eventBus.subscribe(projection);
    ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "rei-tui-agent");
      thread.setDaemon(true);
      return thread;
    });
    AtomicBoolean busy = new AtomicBoolean();
    AtomicReference<String> localOutput = new AtomicReference<>("");
    AgentTuiInput input = new AgentTuiInput();
    TuiInputRouter router = new TuiInputRouter(
        new UserInputService(new UserInputParser()));
    AgentTuiViewModelFactory models = new AgentTuiViewModelFactory();
    AgentTuiRenderer renderer = new AgentTuiRenderer();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    TuiConfig.Builder configBuilder = TuiConfig.builder()
        .rawMode(true).alternateScreen(true).hideCursor(false).mouseCapture(false)
        .tickRate(Duration.ofMillis(100)).errorOutput(originalErr);
    if (shellTerminal != null) {
      configBuilder.backend(new JLineBackend(shellTerminal));
    }

    try (TuiRunner tui = TuiRunner.create(configBuilder.build());
        PrintStream discarded = new PrintStream(OutputStream.nullOutputStream())) {
      System.setOut(discarded);
      System.setErr(discarded);
      tui.run((event, runner) -> {
        if (event instanceof KeyEvent key) {
          if (key.isCtrlC()) {
            if (busy.get()) {
              cancellationService.cancel();
              return true;
            }
            runner.quit();
            return false;
          }
          handleKey(key, input).ifPresent(value ->
              dispatch(value, router, busy, executor, localOutput, runner));
          return true;
        }
        return event instanceof TickEvent || event instanceof ResizeEvent;
      }, frame -> renderer.render(frame, models.create(projection.currentState(), input, busy.get(),
          Math.max(1, frame.height() * 30 / 100 - 2), localOutput.get())));
    } catch (Exception exception) {
      originalErr.println("TUI startup failed: " + exception.getMessage());
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
      subscription.unsubscribe();
      if (busy.get()) cancellationService.cancel();
      executor.shutdownNow();
      try {
        executor.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void dispatch(String value, TuiInputRouter router, AtomicBoolean busy,
      ExecutorService executor, AtomicReference<String> localOutput, TuiRunner runner) {
    TuiInputRouter.Route route = router.route(value, busy.get());
    switch (route.kind()) {
      case CHAT -> {
        localOutput.set("");
        submit(busy, executor, () -> new CommandLine(chatCommand).execute(route.text()));
      }
      case COMMAND -> submit(busy, executor,
          () -> localOutput.set(executeCommand(route.arguments())));
      case EXIT -> runner.quit();
      case MESSAGE -> localOutput.set(route.message());
      case EMPTY -> { }
    }
  }

  String executeCommand(String[] inputArguments) {
    String[] arguments = normalizeSpecialCommand(inputArguments);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      PrintStream previousOut = System.out;
      PrintStream previousErr = System.err;
      try {
        System.setOut(capture);
        System.setErr(capture);
        CommandLine command = new CommandLine(rootCommand.getObject(), commandFactory);
        command.setOut(new PrintWriter(capture, true));
        command.setErr(new PrintWriter(capture, true));
        command.execute(arguments);
      } finally {
        System.setOut(previousOut);
        System.setErr(previousErr);
      }
    }
    String output = bytes.toString(StandardCharsets.UTF_8).stripTrailing();
    return output.isBlank() ? "Command completed." : output;
  }

  private String[] normalizeSpecialCommand(String[] arguments) {
    if (arguments.length == 1 && "help".equals(arguments[0])) return new String[] {"--help"};
    if (arguments.length == 1 && "version".equals(arguments[0])) return new String[] {"--version"};
    return arguments;
  }

  private void submit(AtomicBoolean busy, ExecutorService executor, Runnable operation) {
    if (!busy.compareAndSet(false, true)) return;
    executor.submit(() -> {
      try {
        operation.run();
      } finally {
        busy.set(false);
      }
    });
  }

  private Optional<String> handleKey(KeyEvent key, AgentTuiInput input) {
    if (key.isKey(KeyCode.ENTER)) return input.submit();
    if (key.isDeleteBackward()) input.backspace();
    else if (key.isDeleteForward()) input.delete();
    else if (key.isLeft()) input.left();
    else if (key.isRight()) input.right();
    else if (key.isHome()) input.home();
    else if (key.isEnd()) input.end();
    else if (key.isKey(KeyCode.CHAR) && !key.hasCtrl() && !key.hasAlt()) input.insert(key.codePoint());
    return Optional.empty();
  }
}
