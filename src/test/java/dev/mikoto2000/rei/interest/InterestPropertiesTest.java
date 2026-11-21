package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 関心設定の結果と状態の契約を検証するテスト。 */
class InterestPropertiesTest {
  @Test
  void extractionBudgetsHaveFiniteDefaultsAndRejectUnboundedValues() {
    var properties = new InterestProperties();
    properties.validateExtraction();
    assertEquals(24000, properties.getExtractionMaxInputTokens());
    assertEquals(120, properties.getExtractionTimeoutSeconds());
    assertEquals(4096, properties.getExtractionMaxOutputTokens());
    properties.setExtractionTimeoutSeconds(1200);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, properties::validateExtraction);
    properties.setExtractionTimeoutSeconds(120);
    properties.setExtractionMaxInputTokens(1000000);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, properties::validateExtraction);
    properties.setExtractionMaxInputTokens(24000);
    properties.setExtractionMaxOutputTokens(100000);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, properties::validateExtraction);
  }

  /** 関心の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void topicUpdateIntervalHoursDefaultIs24() {
    InterestProperties props = new InterestProperties();
    assertEquals(24, props.getTopicUpdateIntervalHours());
  }

  /** 関心の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void pastQueryLookbackDaysDefaultIs7() {
    InterestProperties props = new InterestProperties();
    assertEquals(7, props.getPastQueryLookbackDays());
  }
}
