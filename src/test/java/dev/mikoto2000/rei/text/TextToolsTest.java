package dev.mikoto2000.rei.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class TextToolsTest {

  @Test
  void countCharactersReturnsUnicodeCodePointCount() {
    TextTools tools = new TextTools();

    assertEquals(5, tools.countCharacters("hello"));
    assertEquals(5, tools.countCharacters("こんにちは"));
    assertEquals(1, tools.countCharacters("😀"));
    assertEquals(3, tools.countCharacters("a😀b"));
  }

  @Test
  void countCharactersRejectsNullText() {
    TextTools tools = new TextTools();

    assertThrows(IllegalArgumentException.class, () -> tools.countCharacters(null));
  }

  @Test
  void methodToolProviderRegistersCountCharactersTool() throws Exception {
    Tool annotation = TextTools.class.getDeclaredMethod("countCharacters", String.class)
        .getAnnotation(Tool.class);

    assertEquals("countCharacters", annotation.name());
    assertTrue(annotation.description().contains("文字数"));
    assertTrue(annotation.description().contains("Unicode code point"));

    var callback = java.util.Arrays.stream(MethodToolCallbackProvider.builder()
        .toolObjects(new TextTools())
        .build()
        .getToolCallbacks())
        .filter(candidate -> "countCharacters".equals(candidate.getToolDefinition().name()))
        .findFirst()
        .orElseThrow();

    assertTrue(callback.getToolDefinition().inputSchema().contains("text"));
  }
}
