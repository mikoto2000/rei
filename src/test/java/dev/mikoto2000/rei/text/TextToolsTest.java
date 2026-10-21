package dev.mikoto2000.rei.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** テキストツール一覧の結果と状態の契約を検証するテスト。 */
class TextToolsTest {

  /** 文章の整形で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void countCharactersReturnsUnicodeCodePointCount() {
    TextTools tools = new TextTools();

    assertEquals(5, tools.countCharacters("hello"));
    assertEquals(5, tools.countCharacters("こんにちは"));
    assertEquals(1, tools.countCharacters("😀"));
    assertEquals(3, tools.countCharacters("a😀b"));
  }

  /** 件数文字数について、未指定のテキストを受け付けないことを確認する。 */
  @Test
  void countCharactersRejectsNullText() {
    TextTools tools = new TextTools();

    assertThrows(IllegalArgumentException.class, () -> tools.countCharacters(null));
  }

  /** 文章の整形で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void methodToolProviderRegistersCountCharactersTool() throws Exception {
    Tool annotation =
        TextTools.class
            .getDeclaredMethod("countCharacters", String.class)
            .getAnnotation(Tool.class);

    assertEquals("countCharacters", annotation.name());
    assertTrue(annotation.description().contains("文字数"));
    assertTrue(annotation.description().contains("Unicode code point"));

    var callback =
        java.util.Arrays.stream(
                MethodToolCallbackProvider.builder()
                    .toolObjects(new TextTools())
                    .build()
                    .getToolCallbacks())
            .filter(candidate -> "countCharacters".equals(candidate.getToolDefinition().name()))
            .findFirst()
            .orElseThrow();

    assertTrue(callback.getToolDefinition().inputSchema().contains("text"));
  }
}
