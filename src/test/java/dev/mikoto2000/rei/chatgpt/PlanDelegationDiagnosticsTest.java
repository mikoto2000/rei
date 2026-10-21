package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** 計画委譲診断情報の結果と状態の契約を検証するテスト。 */
class PlanDelegationDiagnosticsTest {
  /** 検証に使う設定。 */
  final PlanDelegationProperties settings =
      new PlanDelegationProperties(true, Set.of("default"), Set.of("model"));

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missingAndDisallowedDestinationsHaveDistinctSafeReasons() {
    assertTrue(
        assertThrows(ChatGptException.class, () -> settings.check(null, "model"))
            .getMessage()
            .contains("ACCOUNT_REQUIRED"));
    assertTrue(
        assertThrows(ChatGptException.class, () -> settings.check("default", null))
            .getMessage()
            .contains("MODEL_REQUIRED"));
    var account =
        assertThrows(ChatGptException.class, () -> settings.check("private-account", "model"));
    assertTrue(account.getMessage().contains("ACCOUNT_NOT_ALLOWED"));
    assertFalse(account.getMessage().contains("private-account"));
    assertTrue(
        assertThrows(ChatGptException.class, () -> settings.check("default", "other"))
            .getMessage()
            .contains("MODEL_NOT_ALLOWED"));
    assertDoesNotThrow(() -> settings.check("default", "model"));
  }
}
