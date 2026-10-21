package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebSearchArgumentsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"query\":\"Void\"",
        "\"query\":\"Void\",\"maxResults\":null,\"readTop\":null,\"forceRefresh\":null",
        "\"query\":\"Void\",\"maxResults\":1,\"readTop\":0",
        "\"query\":\"Void\",\"maxResults\":5,\"readTop\":5,\"forceRefresh\":true"
      })
  void acceptsBoundsAndDefaults(String fields) {
    assertDoesNotThrow(() -> validate("{\"request\":{" + fields + "}}"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "[]",
        "{}",
        "{\"query\":\"Void\"}",
        "{\"request\":null}",
        "{\"request\":{}}",
        "{\"request\":{\"query\":\" \"}}",
        "{\"request\":{\"query\":1}}",
        "{\"request\":{\"query\":\"a\",\"query\":\"b\"}}",
        "{\"request\":{\"query\":\"Void\"}} {}",
        "{\"request\":{\"query\":\"Void\",\"forceRefresh\":\"true\"}}"
      })
  void rejectsMalformedArgumentsWithRepairGuidance(String input) {
    var error = assertThrows(InvalidToolArgumentsException.class, () -> validate(input));
    assertTrue(error.getMessage().contains("request"));
    assertTrue(error.getMessage().contains("実行していません"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "6",
        "-1",
        "2147483648",
        "4294967296",
        "1.5",
        "5.0",
        "1e1",
        "true",
        "\"private-value\"",
        "[]",
        "{}"
      })
  void rejectsInvalidMaxResultsWithoutEchoingInput(String value) {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                validate(
                    "{\"request\":{\"query\":\"private-query\",\"maxResults\":" + value + "}}"));
    assertTrue(error.getMessage().contains("maxResults"));
    assertFalse(error.getMessage().contains("private-value"));
    assertFalse(error.getMessage().contains("private-query"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "3", "2147483648", "0.5", "\"0\"", "true"})
  void rejectsReadTopOutsideEffectiveMaxOrWrongType(String value) {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                validate(
                    "{\"request\":{\"query\":\"Void\",\"maxResults\":2,\"readTop\":"
                        + value
                        + "}}"));
    assertTrue(error.getMessage().contains("readTop"));
  }

  @Test
  void rejectsOversizedQueryAndInput() {
    assertThrows(
        InvalidToolArgumentsException.class,
        () -> validate("{\"request\":{\"query\":\"" + "a".repeat(2049) + "\"}}"));
    assertThrows(InvalidToolArgumentsException.class, () -> validate(" ".repeat(16385)));
    assertDoesNotThrow(() -> WebSearchArguments.validate("otherTool", "not JSON", 5));
  }

  @Test
  void usesConfiguredMaximumRatherThanHardCodedFive() {
    String input = "{\"request\":{\"query\":\"Void\",\"maxResults\":8,\"readTop\":8}}";
    assertDoesNotThrow(() -> WebSearchArguments.validate("webSearchAndRead", input, 8));
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () -> WebSearchArguments.validate("webSearchAndRead", input, 3));
    assertTrue(error.getMessage().contains("between 1 and 3"));
    assertThrows(
        IllegalStateException.class,
        () -> WebSearchArguments.validate("webSearchAndRead", input, 0));
  }

  private void validate(String input) {
    WebSearchArguments.validate("webSearchAndRead", input, 5);
  }
}
