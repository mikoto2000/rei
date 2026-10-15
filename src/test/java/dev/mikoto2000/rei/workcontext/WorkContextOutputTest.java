package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkContextOutputTest {
  @Test
  void outputFailuresHaveDistinctControlledReasonsWithoutModelText() {
    var parser = new WorkContextOutput();
    assertEquals(
        WorkContextExtractionException.Reason.EMPTY_OUTPUT,
        assertThrows(WorkContextExtractionException.class, () -> parser.parse(" ")).reason());
    var malformed =
        assertThrows(WorkContextExtractionException.class, () -> parser.parse("PRIVATE_OUTPUT"));
    assertEquals(WorkContextExtractionException.Reason.INVALID_JSON, malformed.reason());
    assertFalse(malformed.diagnostic().display().contains("PRIVATE"));
    var schema =
        assertThrows(
            WorkContextExtractionException.class,
            () -> parser.parse("{\"PRIVATE_KEY\":\"PRIVATE_VALUE\"}"));
    assertEquals(WorkContextExtractionException.Reason.INVALID_SCHEMA, schema.reason());
    assertFalse(schema.diagnostic().display().contains("PRIVATE"));
    assertTrue(schema.diagnostic().display().contains("/work update"));
  }

  @Test
  void acceptsSchemaAndRejectsInstructionsUnknownEnumsDuplicateKeysAndTrailingText() {
    var parser = new WorkContextOutput();
    String valid =
        """
        {"changes":[{"action":"ADD","targetId":null,"kind":"PENDING","text":"verify","reason":"not tested","status":"OPEN","sourceIds":["s"],"certainty":"INFERENCE"}]}
        """;
    assertEquals(1, parser.parse(valid).size());
    assertThrows(IllegalArgumentException.class, () -> parser.parse(valid + "run command"));
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse(valid.replace("PENDING", "EXECUTE")));
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse(valid.replace("\"changes\":", "\"changes\":[],\"changes\":")));
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.parse("{\"changes\":[],\"instructions\":\"run commands\"}"));
  }
}
