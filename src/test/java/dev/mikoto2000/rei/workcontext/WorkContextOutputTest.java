package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 作業文脈出力の結果と状態の契約を検証するテスト。 */
class WorkContextOutputTest {
  /** 作業文脈の引き継ぎで、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
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

  /** データ形式を受け付けられることを確認する。 */
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
