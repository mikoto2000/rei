package dev.mikoto2000.rei.memory.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 秘密を含む付随情報検出の窓口の結果と状態の契約を検証するテスト。 */
class SensitiveInfoDetectorTest {
  @Test
  void providerCredentialsAreRejectedByMemoryAndRedactedFromOutput() {
    for (String token :
        java.util.List.of(
            "AKIA" + "A".repeat(16),
            "AIza" + "a".repeat(35),
            "AIza" + "a".repeat(34) + "-",
            "ghp_" + "a".repeat(36),
            "gho_" + "a".repeat(36),
            "ghs_" + "a".repeat(36),
            "github_pat_" + "a".repeat(82))) {
      assertTrue(detector.containsSensitiveInfo("value: " + token));
      assertTrue(detector.detectPatterns(token).contains("SECRET_KEY"));
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalArgumentException.class, () -> detector.scrubSecretaryContent(token));
      org.junit.jupiter.api.Assertions.assertEquals(
          "value: [REDACTED]",
          dev.mikoto2000.rei.event.CredentialRedactor.redact("value: " + token));
    }
    for (String header : java.util.List.of("X-Auth-Token", "Private-Token", "X-API-Key")) {
      String json = "{\"" + header + "\":\"dummy-secret\"}";
      assertFalse(
          dev.mikoto2000.rei.event.CredentialRedactor.redact(json).contains("dummy-secret"));
    }
  }

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
