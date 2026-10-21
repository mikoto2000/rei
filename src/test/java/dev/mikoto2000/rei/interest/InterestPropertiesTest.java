package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 関心設定の結果と状態の契約を検証するテスト。 */
class InterestPropertiesTest {

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
