package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 作業状況Extractionの結果と状態の契約を検証するテスト。 */
class ActivityExtractionTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void requestSchemaAllowsOnlyCapturedMonitorsAndDoesNotRetainPreviousIds() {
    var mapper = new tools.jackson.databind.json.JsonMapper();
    var ids = List.of("\\\\.\\DISPLAY1", "display-\"2\"");
    var schema = mapper.readTree(ActivityOutputParser.schemaForMonitors(ids));
    var allowed = schema.at("/properties/activities/items/properties/monitor/enum");
    assertEquals(
        ids,
        java.util.stream.StreamSupport.stream(allowed.spliterator(), false)
            .map(n -> n.asString())
            .toList());
    var validator =
        com.networknt.schema.SchemaRegistry.withDefaultDialect(
                com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema);
    assertFalse(validator.validate(mapper.readTree(VALID)).isEmpty());
    var validSchema = mapper.readTree(ActivityOutputParser.schemaForMonitors(List.of("m1", "m2")));
    assertTrue(
        com.networknt.schema.SchemaRegistry.withDefaultDialect(
                com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
            .getSchema(validSchema)
            .validate(mapper.readTree(VALID))
            .isEmpty());
    assertTrue(
        mapper
            .readTree(ActivityOutputParser.SCHEMA)
            .at("/properties/activities/items/properties/monitor/enum")
            .isMissingNode());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void diagnosticIdentifiesConfidenceConstraintWithoutOutputValues() {
    var error =
        assertThrows(
            ActivityOutputParser.InvalidOutput.class,
            () ->
                new ActivityOutputParser()
                    .parse(VALID.replace("0.86", "1.2"), List.of("m1", "m2")));
    assertEquals("/confidence:maximum", error.diagnostic());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void emptyOutputHasDistinctDiagnostic() {
    var error =
        assertThrows(
            ActivityOutputParser.InvalidOutput.class,
            () -> new ActivityOutputParser().parse("   ", List.of("m1")));
    assertEquals("/:empty_output", error.diagnostic());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void diagnosticCannotLeakArbitraryPathsOrCodes() {
    var error =
        new ActivityOutputParser.InvalidOutput(
            List.of(
                new ActivityOutputParser.ResultError("/PRIVATE_TITLE\nsecret", "PRIVATE_PAYLOAD")));
    assertEquals("/:validation_error", error.diagnostic());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void diagnosticIdentifiesActivityField() {
    var error =
        assertThrows(
            ActivityOutputParser.InvalidOutput.class,
            () ->
                new ActivityOutputParser()
                    .parse(
                        VALID.replace("\"service\":\"X\"", "\"service\":null"),
                        List.of("m1", "m2")));
    assertEquals("/activities/0/service:type", error.diagnostic());
  }

  /** 検証に使う有効な。 */
  static final String VALID =
      """
      {"summary":"X and YouTube are visible", "confidence":0.86,"activities":[
        {"monitor":"m1","type":"social","application":"Firefox","service":"X","contentTitle":"","projectCandidate":""},
        {"monitor":"m2","type":"media","application":"Firefox","service":"YouTube","contentTitle":"Xxx","projectCandidate":""}]}
      """;

  /** 並列作業状況の記録を保持することを確認する。 */
  @Test
  void retainsParallelActivitiesAndConfidence() {
    var result = new ActivityOutputParser().parse(VALID, List.of("m1", "m2"));
    assertEquals(2, result.inference().activities().size());
    assertEquals(.86, result.confidence());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidJsonRejected() {
    assertThrows(
        ActivityOutputParser.InvalidOutput.class,
        () -> new ActivityOutputParser().parse("oops", List.of("m1")));
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidConfidenceRejected() {
    assertThrows(
        ActivityOutputParser.InvalidOutput.class,
        () -> new ActivityOutputParser().parse(VALID.replace("0.86", "1.2"), List.of("m1", "m2")));
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void inventedMonitorRejected() {
    assertThrows(
        ActivityOutputParser.InvalidOutput.class,
        () -> new ActivityOutputParser().parse(VALID, List.of("m1")));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void modelCannotReplaceObservation() {
    assertThrows(
        ActivityOutputParser.InvalidOutput.class,
        () ->
            new ActivityOutputParser()
                .parse(
                    VALID.replace("\"confidence\":0.86", "\"observations\":[],\"confidence\":0.86"),
                    List.of("m1", "m2")));
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void trailingJsonRejected() {
    assertThrows(
        ActivityOutputParser.InvalidOutput.class,
        () -> new ActivityOutputParser().parse(VALID + "{}", List.of("m1", "m2")));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extensibleCategory() {
    assertEquals(
        "new-category",
        new ActivityOutputParser()
            .parse(VALID.replace("social", "new-category"), List.of("m1", "m2"))
            .inference()
            .activities()
            .getFirst()
            .type());
  }
}
