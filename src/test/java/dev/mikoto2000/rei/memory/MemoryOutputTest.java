package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.memory.service.MemoryOutput;
import org.junit.jupiter.api.Test;

/** 記憶出力の結果と状態の契約を検証するテスト。 */
class MemoryOutputTest {
  /** 検証に使う有効な。 */
  static final String VALID =
      """
      {"memories":[{"type":"LESSON","scope":"PROJECT","content":"Caps Lock was unreliable in this environment",
      "summary":"Hotkey lesson","confidence":0.9,"importance":0.8,"sourceTurnIds":["t"],"tags":["hotkey"]}]}
      """;

  /** 長期記憶の活用で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parsesGroundedLesson() {
    assertEquals(MemoryType.LESSON, new MemoryOutput().candidates(VALID).getFirst().type());
  }

  /** データ形式満たしていない条件を受け付けないことを確認する。 */
  @Test
  void rejectsSchemaViolations() {
    for (String value :
        new String[] {
          VALID.replace("0.9", "1.9"),
          VALID.replace("LESSON", "UNKNOWN"),
          VALID.replace("[\"t\"]", "[]"),
          VALID.replace("\"PROJECT\"", "\"PERMANENT\""),
          "[]",
          "{\"memories\":null}"
        }) assertThrows(IllegalArgumentException.class, () -> new MemoryOutput().candidates(value));
  }

  /** 長期記憶の活用で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void rejectsFencesTrailingValuesAndDuplicateKeys() {
    for (String value :
        new String[] {
          "```json\n" + VALID + "```", VALID + "{}", "{\"memories\":[],\"memories\":[]}"
        }) assertThrows(RuntimeException.class, () -> new MemoryOutput().candidates(value));
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void emptyCandidatesAreValid() {
    assertTrue(new MemoryOutput().candidates("{\"memories\":[]}").isEmpty());
  }

  /** 解決結果について、既知操作を必要とすることを確認する。 */
  @Test
  void resolutionRequiresKnownActionAndNoAdditionalFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemoryOutput().resolution("{\"action\":\"DELETE\",\"targetIds\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MemoryOutput().resolution("{\"action\":\"NEW\",\"targetIds\":[],\"extra\":true}"));
  }
}
