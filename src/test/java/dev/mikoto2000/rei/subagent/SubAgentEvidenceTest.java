package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 補助エージェント根拠の結果と状態の契約を検証するテスト。 */
class SubAgentEvidenceTest {
  /** 検証に使う保存や受け渡しに用いるデータ形式の変換。 */
  final JsonMapper mapper = JsonMapper.builder().build();

  /** {@code answer} の条件を再現するための補助情報を返す。 */
  String answer(String status, Object evidence) {
    return mapper.writeValueAsString(
        Map.of(
            "status",
            status,
            "summary",
            "done",
            "result",
            Map.of("evidence", evidence),
            "warnings",
            List.of()));
  }

  /** {@code claim} の条件を再現するための補助情報を返す。 */
  Map<String, String> claim(String receipt, String quote) {
    var json = new SubAgentResultParser().parse(receipt);
    return Map.of(
        "evidenceId",
        json.get("evidenceId").asString(),
        "tool",
        json.get("tool").asString(),
        "outputSha256",
        json.get("outputSha256").asString(),
        "quote",
        quote);
  }

  /** 成功について、観測必須ツール一覧を必要とすることを確認する。 */
  @Test
  void successRequiresObservedRequiredToolsAndMatchingQuotes() {
    var ledger = new SubAgentEvidence();
    var json = new SubAgentResultParser().parse(answer("SUCCESS", List.of()));
    assertFalse(ledger.validate(List.of("readMultiFile"), json).valid());
    String receipt = ledger.capture("readMultiFile", "{}", "actual file content");
    var valid =
        new SubAgentResultParser().parse(answer("SUCCESS", List.of(claim(receipt, "actual file"))));
    assertTrue(ledger.validate(List.of("readMultiFile"), valid).valid());
    assertFalse(new SubAgentEvidence().validate(List.of("readMultiFile"), valid).valid());
    assertFalse(
        ledger
            .validate(
                List.of("readMultiFile"),
                new SubAgentResultParser()
                    .parse(answer("SUCCESS", List.of(claim(receipt, "invented secret")))))
            .valid());
  }

  /** 未指定作業を伝えることを確認する。 */
  @Test
  void partialMayReportMissingWorkButCannotForgeEvidenceOrDuplicateReferences() {
    var ledger = new SubAgentEvidence();
    assertTrue(
        ledger
            .validate(
                List.of("readMultiFile"),
                new SubAgentResultParser().parse(answer("PARTIAL", List.of())))
            .valid());
    var reference = claim(ledger.capture("readMultiFile", "{}", "observed"), "observed");
    var forged = new HashMap<>(reference);
    forged.put("outputSha256", "0".repeat(64));
    var failure =
        ledger.validate(
            List.of("readMultiFile"),
            new SubAgentResultParser().parse(answer("PARTIAL", List.of(forged))));
    assertFalse(failure.valid());
    assertTrue(failure.errors().stream().noneMatch(e -> e.message().contains("observed")));
    assertFalse(
        ledger
            .validate(
                List.of("readMultiFile"),
                new SubAgentResultParser().parse(answer("SUCCESS", List.of(reference, reference))))
            .valid());
  }

  /** 補助エージェントへの委譲で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void receiptOutputIsBoundedAndDisclosesTruncation() {
    var ledger = new SubAgentEvidence();
    var receipt =
        new SubAgentResultParser().parse(ledger.capture("readMultiFile", "{}", "x".repeat(20000)));
    assertTrue(receipt.get("truncated").asBoolean());
    assertEquals(16384, receipt.get("output").asString().length());
    assertFalse(
        ledger
            .validate(
                List.of("readMultiFile"),
                new SubAgentResultParser().parse(answer("SUCCESS", Map.of())))
            .valid());
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void successRequiresTheSpecifiedArgumentsOnCitedActualCalls() {
    var ledger = new SubAgentEvidence();
    var required =
        List.of(
            new SubAgentRequiredCall("readMultiFile", "{\"paths\":[\"README.md\"],\"limit\":10}"));
    var wrong =
        claim(
            ledger.capture(
                "readMultiFile", "{\"paths\":[\"OTHER.md\"],\"limit\":10}", "actual content"),
            "actual content");
    assertFalse(
        ledger
            .validate(
                List.of("readMultiFile"),
                required,
                new SubAgentResultParser().parse(answer("SUCCESS", List.of(wrong))))
            .valid());
    var right =
        claim(
            ledger.capture(
                "readMultiFile", "{ \"limit\":10, \"paths\":[\"README.md\"] }", "actual content"),
            "actual content");
    assertFalse(
        ledger
            .validate(
                List.of("readMultiFile"),
                required,
                new SubAgentResultParser().parse(answer("SUCCESS", List.of(wrong))))
            .valid());
    assertTrue(
        ledger
            .validate(
                List.of("readMultiFile"),
                required,
                new SubAgentResultParser().parse(answer("SUCCESS", List.of(right))))
            .valid());
    assertTrue(
        ledger
            .validate(
                List.of("readMultiFile"),
                required,
                new SubAgentResultParser().parse(answer("PARTIAL", List.of(wrong))))
            .valid());
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void requiredArgumentsMustBeBoundedJsonObjects() {
    assertThrows(
        IllegalArgumentException.class, () -> new SubAgentRequiredCall("readMultiFile", "[]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubAgentRequiredCall("readMultiFile", "not-json"));
    assertThrows(
        IllegalArgumentException.class, () -> new SubAgentRequiredCall("readMultiFile", "{} {}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubAgentRequiredCall("readMultiFile", "{\"path\":1,\"path\":2}"));
    assertThrows(IllegalArgumentException.class, () -> new SubAgentRequiredCall("bad tool", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SubAgentRequiredCall("readMultiFile", "{\"value\":\"" + "x".repeat(4096) + "\"}"));
  }
}
