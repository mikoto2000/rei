package dev.mikoto2000.rei.feed.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class FeedCommandHelpEncodingTest {

  @Test
  void helpContainsReadableJapaneseDescription() {
    CommandLine commandLine = new CommandLine(new FeedCommand());
    StringWriter writer = new StringWriter();

    commandLine.usage(new PrintWriter(writer));

    assertThat(writer.toString())
        .contains("RSS/Atom フィードを操作します")
        .contains("フィードを追加します")
        .contains("登録済みフィードを一覧します");
  }
}
