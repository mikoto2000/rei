package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 利用者入力入力の解釈の結果と状態の契約を検証するテスト。 */
class UserInputParserTest {

  /** 検証に使う入力の解釈。 */
  private final UserInputParser parser = new UserInputParser();

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void classifiesEmptyChatAndSlashInput() {
    assertEquals(UserInputParser.Kind.EMPTY, parser.parse("  ").kind());
    assertEquals(UserInputParser.Kind.CHAT, parser.parse("こんにちは").kind());
    assertEquals(UserInputParser.Kind.SLASH_COMMAND, parser.parse(" /model foo ").kind());
    assertArrayEquals(new String[] {"model", "foo"}, parser.parse(" /model foo ").arguments());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void appliesShellQuotingRulesToSlashCommands() {
    assertArrayEquals(
        new String[] {"project", "add", "C:\\work dir"},
        parser.parse("/project add \"C:\\work dir\"").arguments());
  }

  /** 共通の実行基盤で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void recognizesSummarizeSlashCommand() {
    UserInputParser.ParsedInput parsed = parser.parse("/summarize https://example.com");

    assertEquals(UserInputParser.Kind.SLASH_COMMAND, parsed.kind());
    assertArrayEquals(new String[] {"summarize", "https://example.com"}, parsed.arguments());
  }
}
