package dev.mikoto2000.rei.memory.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 記憶設定の結果と状態の契約を検証するテスト。 */
class MemoryPropertiesTest {

  /** 長期記憶の準備で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaultsAreAppliedWhenZeroOrNull() {
    MemoryProperties props = new MemoryProperties(true, 0, 0, 0, 0, 0, 0, null);

    assertEquals(20, props.autoTriggerMessageThreshold());
    assertEquals(80, props.autoTriggerContextPercent());
    assertEquals(10, props.searchMaxResults());
    assertEquals(3, props.searchMaxInjected());
    assertEquals(2000, props.summarizeMaxLength());
    assertEquals(60, props.conflictTimeoutSeconds());
    assertEquals(30, props.expiry().shortTermDays());
    assertEquals(365, props.expiry().longTermDays());
  }
}
