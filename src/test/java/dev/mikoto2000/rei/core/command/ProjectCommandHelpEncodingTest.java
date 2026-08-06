package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class ProjectCommandHelpEncodingTest {

  @Test
  void helpContainsReadableJapaneseDescription() {
    CommandLine commandLine = new CommandLine(new ProjectCommand());
    StringWriter writer = new StringWriter();

    commandLine.usage(new PrintWriter(writer));

    assertThat(writer.toString()).contains("作業ディレクトリ");
  }
}
