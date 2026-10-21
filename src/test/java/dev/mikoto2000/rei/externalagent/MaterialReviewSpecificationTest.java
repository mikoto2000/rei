package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.subagent.SubAgentResultParser;
import dev.mikoto2000.rei.subagent.SubAgentResultSchema;
import dev.mikoto2000.rei.subagent.SubAgentValidationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 教材レビュー仕様の結果と状態の契約を検証するテスト。 */
class MaterialReviewSpecificationTest {
  /** {@code fixture} の条件を再現するための補助情報を返す。 */
  static String fixture() throws Exception {
    try (var stream =
        MaterialReviewSpecificationTest.class.getResourceAsStream(
            "/external-agent/material-review-valid.json")) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** 外部エージェントへの委譲で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void promptDefinesCompleteTeachingContract() throws Exception {
    try (var stream = getClass().getResourceAsStream("/external-agent/material-review.md")) {
      assertNotNull(stream, "Versioned material task specification must exist");
      String prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      for (String criterion :
          new String[] {
            "Technical Accuracy",
            "Instructional Design",
            "Presentation / Teaching Usability",
            "Reader Comprehension",
            "Code Samples",
            "Markdown / Documentation Site Quality",
            "Cross-page Consistency",
            "Practical Applicability",
            "Content Volume and Time Allocation",
            "Critical",
            "High",
            "Medium",
            "Low",
            "Positive Findings",
            "Recommended Fix Order",
            "全ページ読了後",
            "全面書き換え",
            "受講者"
          }) assertTrue(prompt.contains(criterion), criterion);
    }
  }

  /** データ形式について、不正な重大度未指定項目一覧を受け付けないことを確認する。 */
  @Test
  void schemaRejectsInvalidSeverityMissingFieldsAndScores() throws Exception {
    var schema =
        SubAgentResultSchema.load(
            Path.of("."), "classpath:/subagents/schemas/material-review.schema.json");
    var parser = new SubAgentResultParser();
    String valid = fixture();
    assertTrue(schema.validate(parser.parse(valid)).isEmpty());
    for (String invalid :
        new String[] {
          valid.replace("\"HIGH\"", "\"UNKNOWN\""),
          valid.replace("\"summary\"", "\"missing\""),
          valid.replace("\"score\": 4", "\"score\": 6"),
          valid.replace("\"whyItMatters\"", "\"missingReason\""),
          valid.replace("\"score\": 4", "\"score\": -1"),
          valid.replace("\"line\": 3", "\"line\": 0")
        }) assertFalse(schema.validate(parser.parse(invalid)).isEmpty());
    assertThrows(SubAgentValidationException.class, () -> parser.parse("{broken"));
    assertThrows(SubAgentValidationException.class, () -> parser.parse(valid + " {}"));
  }
}
