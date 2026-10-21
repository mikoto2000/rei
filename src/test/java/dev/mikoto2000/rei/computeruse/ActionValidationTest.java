package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 操作検証の結果と状態の契約を検証するテスト。 */
class ActionValidationTest {
  /** 上限内に収めた説明を受け付けられることを確認する。 */
  @Test
  void acceptsBoundedExplanationWithoutChangingTheAction() {
    String response =
        json("PRESS_KEY", "key", "\"TAB\"")
            .replace("\"reason\":null", "\"reason\":\"Focus the next field\"");
    assertEquals(
        new ComputerAction.PressKey("TAB", ComputerAction.Risk.LOW),
        new ActionParser().parse(response, ComputerUseServiceTest.screen()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActionParser()
                .parse(
                    response.replace("Focus the next field", "x".repeat(301)),
                    ComputerUseServiceTest.screen()));
  }

  /** 画面操作の支援で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void distinguishesMissingAndOversizedOutput() {
    var parser = new ActionParser();
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse(null, ComputerUseServiceTest.screen()))
            .getMessage()
            .contains("No response text"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("x".repeat(30001), ComputerUseServiceTest.screen()))
            .getMessage()
            .contains("30001"));
  }

  /** 画面操作の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void diagnosticsNeverIncludeModelSuppliedValues() {
    for (String invalid :
        java.util.List.of(
            "secret-content",
            json("DONE", "reason", "\"Visible\"").replace("\"LOW\"", "\"secret-content\""),
            json("PRESS_KEY", "key", "\"secret-content\""),
            json("DONE", "text", "\"secret-content\""))) {
      var error =
          assertThrows(
              InvalidComputerDecision.class,
              () -> new ActionParser().parse(invalid, ComputerUseServiceTest.screen()));
      assertFalse(error.getMessage().contains("secret-content"));
      assertNull(error.getCause());
    }
  }

  /** {@code json} の条件を再現するための補助情報を返す。 */
  static String json(String action, String field, String value) {
    var fields = new java.util.LinkedHashMap<String, String>();
    for (String name :
        new String[] {"target", "confidence", "text", "key", "amount", "millis", "reason"})
      fields.put(name, "null");
    fields.put(field, value);
    return "{\"action\":\""
        + action
        + "\",\"risk\":\"LOW\","
        + fields.entrySet().stream()
            .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
            .collect(java.util.stream.Collectors.joining(","))
        + "}";
  }

  /** 画面操作の支援で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parsesExactlyOneDecision() {
    var parser = new ActionParser();
    assertEquals(
        new ComputerAction.Done("Visible"),
        parser.parse(json("DONE", "reason", "\"Visible\""), ComputerUseServiceTest.screen()));
    assertEquals(
        new ComputerAction.TypeText("日本語😀", ComputerAction.Risk.LOW),
        parser.parse(json("TYPE_TEXT", "text", "\"日本語😀\""), ComputerUseServiceTest.screen()));
  }

  /** 対象付随情報を保持することを確認する。 */
  @Test
  void decodesAllSupportedActionsAndRetainsTargetMetadata() {
    var parser = new ActionParser();
    for (String type : new String[] {"CLICK", "DOUBLE_CLICK"}) {
      String click =
          json(type, "target", "{\"description\":\"Save\",\"centerX\":0.25,\"centerY\":0.5}")
              .replace("\"confidence\":null", "\"confidence\":0.94");
      var action = parser.parse(click, ComputerUseServiceTest.screen());
      var metadata = ComputerUseService.progress(1, "decided", action);
      assertEquals("Save", metadata.target());
      assertEquals(80, metadata.x());
      assertEquals(120, metadata.y());
      assertEquals(.94, metadata.confidence());
    }
    assertInstanceOf(
        ComputerAction.PressKey.class,
        parser.parse(json("PRESS_KEY", "key", "\"TAB\""), ComputerUseServiceTest.screen()));
    assertInstanceOf(
        ComputerAction.Scroll.class,
        parser.parse(json("SCROLL", "amount", "-2"), ComputerUseServiceTest.screen()));
    assertInstanceOf(
        ComputerAction.Wait.class,
        parser.parse(json("WAIT", "millis", "100"), ComputerUseServiceTest.screen()));
    assertInstanceOf(
        ComputerAction.Uncertain.class,
        parser.parse(json("UNCERTAIN", "reason", "\"Loading\""), ComputerUseServiceTest.screen()));
    assertInstanceOf(
        ComputerAction.Failed.class,
        parser.parse(
            json("FAILED", "reason", "\"Cannot continue\""), ComputerUseServiceTest.screen()));
  }

  /** 重複の有無追加を受け付けないことを確認する。 */
  @Test
  void rejectsDuplicateExtraAndConflictingFields() {
    var valid = json("DONE", "reason", "\"Visible\"");
    for (String invalid :
        java.util.List.of(
            valid.replace("\"action\":", "\"extra\":1,\"action\":"),
            valid.replace("\"action\":", "\"action\":\"CLICK\",\"action\":"),
            valid.replace("\"text\":null", "\"text\":\"conflicting\"")))
      assertThrows(
          IllegalArgumentException.class,
          () -> new ActionParser().parse(invalid, ComputerUseServiceTest.screen()));
  }

  /** 不正なJSON変換を受け付けないことを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"[]", "{}", "null", "{\"action\":\"CLICK\"}", "```json\n{}\n```"})
  void rejectsInvalidJson(String json) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActionParser().parse(json, ComputerUseServiceTest.screen()));
  }

  /** 未指定を受け付けないことを確認する。 */
  @Test
  void rejectsMissingAndUnknownAndWrongTypes() {
    var parser = new ActionParser();
    for (String invalid :
        java.util.List.of(
            json("DONE", "reason", "null"),
            json("CLICK", "target", "null"),
            json("BOGUS", "reason", "\"unknown\""),
            json("WAIT", "millis", "\"100\""),
            json("WAIT", "millis", "0"),
            json("WAIT", "millis", "100000"),
            json("PRESS_KEY", "key", "\"UNKNOWN\""),
            json("DONE", "reason", "\"ok\"") + "{}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> parser.parse(invalid, ComputerUseServiceTest.screen()),
          invalid);
    }
  }

  /** 画面操作の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void validatesCoordinatesAndConfidenceEvenForInjectedModels() {
    for (double confidence : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ActionValidator.validate(
                  new ComputerAction.Click(
                      new ComputerAction.Target(2, 3, "button"),
                      confidence,
                      ComputerAction.Risk.LOW),
                  ComputerUseServiceTest.screen()));
    }
    for (var point :
        java.util.List.of(
            new int[] {-1, 0}, new int[] {0, -1}, new int[] {320, 0}, new int[] {0, 240})) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ActionValidator.validate(
                  new ComputerAction.Click(
                      new ComputerAction.Target(point[0], point[1], "button"),
                      .9,
                      ComputerAction.Risk.LOW),
                  ComputerUseServiceTest.screen()));
    }
  }
}
