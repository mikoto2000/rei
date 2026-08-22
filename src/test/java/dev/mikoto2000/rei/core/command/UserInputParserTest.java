package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserInputParserTest {

  private final UserInputParser parser = new UserInputParser();

  @Test
  void classifiesEmptyChatAndSlashInput() {
    assertEquals(UserInputParser.Kind.EMPTY, parser.parse("  ").kind());
    assertEquals(UserInputParser.Kind.CHAT, parser.parse("こんにちは").kind());
    assertEquals(UserInputParser.Kind.SLASH_COMMAND, parser.parse(" /model foo ").kind());
    assertArrayEquals(new String[] {"model", "foo"}, parser.parse(" /model foo ").arguments());
  }

  @Test
  void appliesShellQuotingRulesToSlashCommands() {
    assertArrayEquals(new String[] {"project", "add", "C:\\work dir"},
        parser.parse("/project add \"C:\\work dir\"").arguments());
  }
}
