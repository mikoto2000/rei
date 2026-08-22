package dev.mikoto2000.rei.ui.tui;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.command.ChatCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.ui.projection.DefaultAgentUiProjection;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** TamboUI + JLine backend で Agent UI Projection を描画する TUI entry point。 */
@Component
@Command(name = "tui", description = "TamboUI Agent console")
public final class AgentTuiCommand implements Runnable {

  private final AgentEventBus eventBus;
  private final ChatCommand chatCommand;
  private final CommandCancellationService cancellationService;

  public AgentTuiCommand(AgentEventBus eventBus, ChatCommand chatCommand,
      CommandCancellationService cancellationService) {
    this.eventBus = eventBus;
    this.chatCommand = chatCommand;
    this.cancellationService = cancellationService;
  }

  @Override
  public void run() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    AgentEventBus.Subscription subscription = eventBus.subscribe(projection);
    ExecutorService agentExecutor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "rei-tui-agent");
      thread.setDaemon(true);
      return thread;
    });
    AtomicBoolean agentBusy = new AtomicBoolean();
    AgentTuiInput input = new AgentTuiInput();
    AgentTuiViewModelFactory models = new AgentTuiViewModelFactory();
    AgentTuiRenderer renderer = new AgentTuiRenderer();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;

    TuiConfig config = TuiConfig.builder()
        .rawMode(true)
        .alternateScreen(true)
        .hideCursor(false)
        .mouseCapture(false)
        .tickRate(Duration.ofMillis(100))
        .errorOutput(originalErr)
        .build();

    try (TuiRunner tui = TuiRunner.create(config);
        PrintStream discardedOutput = new PrintStream(OutputStream.nullOutputStream())) {
      // ChatCommand と Tool の従来 console 出力を alternate screen へ混入させない。
      System.setOut(discardedOutput);
      System.setErr(discardedOutput);
      tui.run(
          (event, runner) -> {
            if (event instanceof KeyEvent key) {
              if (key.isCtrlC()) {
                if (agentBusy.get()) {
                  cancellationService.cancel();
                }
                runner.quit();
                return false;
              }
              Optional<String> submitted = handleKey(key, input, agentBusy.get());
              submitted.ifPresent(prompt -> submit(prompt, agentBusy, agentExecutor));
              return true;
            }
            return event instanceof TickEvent || event instanceof ResizeEvent;
          },
          frame -> renderer.render(frame,
              models.create(projection.currentState(), input, agentBusy.get(),
                  Math.max(1, frame.height() * 30 / 100 - 2))));
    } catch (Exception exception) {
      originalErr.println("[error] TUI failed: " + exception.getMessage());
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
      subscription.unsubscribe();
      if (agentBusy.get()) {
        cancellationService.cancel();
      }
      agentExecutor.shutdownNow();
      try {
        agentExecutor.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private Optional<String> handleKey(KeyEvent key, AgentTuiInput input, boolean running) {
    if (key.isKey(KeyCode.ENTER)) {
      return input.submit(running);
    }
    if (key.isDeleteBackward()) {
      input.backspace();
    } else if (key.isDeleteForward()) {
      input.delete();
    } else if (key.isLeft()) {
      input.left();
    } else if (key.isRight()) {
      input.right();
    } else if (key.isHome()) {
      input.home();
    } else if (key.isEnd()) {
      input.end();
    } else if (key.isKey(KeyCode.CHAR) && !key.hasCtrl() && !key.hasAlt()) {
      input.insert(key.codePoint());
    }
    return Optional.empty();
  }

  private void submit(String prompt, AtomicBoolean agentBusy, ExecutorService executor) {
    if (!agentBusy.compareAndSet(false, true)) {
      return;
    }
    executor.submit(() -> {
      try {
        new CommandLine(chatCommand).execute(prompt);
      } finally {
        agentBusy.set(false);
      }
    });
  }
}
