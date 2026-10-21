package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Bluesky設定の結果と状態の契約を検証するテスト。 */
class BlueskyPropertiesTest {

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaults() {
    BlueskyProperties props = new BlueskyProperties();
    assertFalse(props.isEnabled());
    assertEquals("", props.getHandle());
    assertEquals("", props.getAppPassword());
    assertEquals(300, props.getMaxPostLength());
    assertEquals(30, props.getTimeoutSeconds());
    assertEquals(1200, props.getReply().getGenerationTimeoutSeconds());
  }
}
