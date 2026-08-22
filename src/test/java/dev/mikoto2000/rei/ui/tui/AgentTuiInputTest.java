package dev.mikoto2000.rei.ui.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentTuiInputTest {

  @Test
  void editsTextAtCursorIncludingJapaneseAndSupplementaryCharacters() {
    AgentTuiInput input = new AgentTuiInput();
    "日本語😀".codePoints().forEach(input::insert);
    input.left();
    input.backspace();
    input.insert('本');

    assertEquals("日本本😀", input.text());
    assertEquals("日本本", input.textBeforeCursor());
    input.delete();
    assertEquals("日本本", input.text());
  }

  @Test
  void supportsHomeEndAndHorizontalMovement() {
    AgentTuiInput input = new AgentTuiInput();
    "abc".codePoints().forEach(input::insert);
    input.home();
    input.right();
    input.insert('X');
    input.end();
    input.left();
    input.insert('Y');

    assertEquals("aXbYc", input.text());
  }

  @Test
  void submitClearsAcceptedInput() {
    AgentTuiInput input = new AgentTuiInput();
    "hello".codePoints().forEach(input::insert);

    assertEquals("hello", input.submit().orElseThrow());
    assertEquals("", input.text());
  }

  @Test
  void emptySubmitIsRejected() {
    AgentTuiInput input = new AgentTuiInput();
    assertTrue(input.submit().isEmpty());
  }
}
