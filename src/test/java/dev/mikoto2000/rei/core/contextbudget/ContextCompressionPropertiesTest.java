package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 文脈圧縮設定の結果と状態の契約を検証するテスト。 */
class ContextCompressionPropertiesTest {
  /** 既定値終点二つ分数およびについて、境界条件を受け付けられることを確認する。 */
  @Test
  void defaultsToTwoMinutesAndAcceptsBoundaries() {
    var properties = new ContextCompressionProperties();
    assertThat(properties.getRetryCooldownSeconds()).isEqualTo(120);
    properties.validate();
    properties.setRetryCooldownSeconds(1);
    properties.validate();
    properties.setRetryCooldownSeconds(3600);
    properties.validate();
  }

  /** 上限のないを受け付けないことを確認する。 */
  @Test
  void rejectsUnboundedOrDisabledRetryCooldowns() {
    var properties = new ContextCompressionProperties();
    for (int seconds : new int[] {-1, 0, 3601}) {
      properties.setRetryCooldownSeconds(seconds);
      assertThatThrownBy(properties::validate)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rei.context-compression.retry-cooldown-seconds", "1", "3600");
    }
  }
}
