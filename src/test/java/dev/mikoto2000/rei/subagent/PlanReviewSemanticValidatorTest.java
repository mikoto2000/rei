package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** 計画レビュー意味による検証の窓口の結果と状態の契約を検証するテスト。 */
class PlanReviewSemanticValidatorTest {
  /** 補助エージェントへの委譲で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateFindingsAndUnsupportedLocationsRemainUnsupported() {
    var parser = new SubAgentResultParser();
    var result =
        new PlanReviewSemanticValidator()
            .validate(
                parser.parse(
                    "{\"findings\":[{\"message\":\"x\",\"location\":\"other.md:1\"},{\"message\":\"x\",\"location\":\"other.md:1\"}],\"references\":[{\"path\":\"design.md\"}],\"unverified\":[]}"));
    assertEquals(PlanReviewSemanticValidator.Support.UNSUPPORTED, result.support());
    assertFalse(result.truthVerified());
    assertFalse(result.issues().isEmpty());
  }

  /** 補助エージェントへの委譲で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void traceableFindingsArePartialSupportRatherThanTruth() {
    var result =
        new PlanReviewSemanticValidator()
            .validate(
                new SubAgentResultParser()
                    .parse(
                        "{\"findings\":[{\"message\":\"x\",\"location\":\"design.md:1\"}],\"references\":[{\"path\":\"design.md\"}],\"unverified\":[]}"));
    assertEquals(PlanReviewSemanticValidator.Support.PARTIALLY_SUPPORTED, result.support());
    assertFalse(result.truthVerified());
  }
}
