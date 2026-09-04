package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.ui.shell.RootCommand;
import picocli.CommandLine;

class RootCommandImageTest {

  @Test
  void rootCommandRegistersImageCommand() {
    CommandLine commandLine = new CommandLine(new RootCommand());

    assertThat(commandLine.getSubcommands()).containsKey("image");
  }

  @Test
  void rootCommandDoesNotRegisterRemovedTuiCommand() {
    CommandLine commandLine = new CommandLine(new RootCommand());

    assertThat(commandLine.getSubcommands()).doesNotContainKey("tui");
    assertThat(commandLine.getUsageMessage()).doesNotContain("tui");
  }
}
