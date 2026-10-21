package dev.mikoto2000.rei.memory.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 秘密を含む付随情報検出の窓口の結果と状態の契約を検証するテスト。 */
class SensitiveInfoDetectorTest {

  /** 検証に使う検出の窓口。 */
  private final SensitiveInfoDetector detector = new SensitiveInfoDetector();

  /** 長期記憶の補助で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void detectsEmail() {
    assertTrue(detector.containsSensitiveInfo("user@example.com"));
  }

  /** 長期記憶の補助で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void detectsSecretPattern() {
    assertTrue(detector.containsSensitiveInfo("password=abc123"));
  }

  /** 長期記憶の補助で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ignoresNormalText() {
    assertFalse(detector.containsSensitiveInfo("hello world"));
  }
}
