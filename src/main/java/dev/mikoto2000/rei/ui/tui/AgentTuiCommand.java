package dev.mikoto2000.rei.ui.tui;

import org.springframework.stereotype.Component;

import picocli.CommandLine.Command;

/** Picocli adapter for entering the shared TUI launcher from the shell. */
@Component
@Command(name = "tui", description = "TamboUI Agent console")
public final class AgentTuiCommand implements Runnable {
  private final AgentTuiLauncher launcher;

  public AgentTuiCommand(AgentTuiLauncher launcher) {
    this.launcher = launcher;
  }

  @Override
  public void run() {
    launcher.run(null);
  }

  public void run(org.jline.terminal.Terminal shellTerminal) {
    launcher.run(shellTerminal);
  }
}
