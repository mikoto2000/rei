package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class RootCommandImageTest {

  @Test
  void rootCommandRegistersImageCommand() {
    CommandLine commandLine = new CommandLine(new RootCommand());

    assertThat(commandLine.getSubcommands()).containsKey("image");
  }
}
