package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** LiveE2E判断基準の結果と状態の契約を検証するテスト。 */
class LiveE2EPolicyTest {
  /** 判定状態一覧既定無効およびについて、明示的な有効を必要とすることを確認する。 */
  @Test
  void flagsDefaultOffAndRequireExplicitTrue() {
    assertFalse(LiveE2EPolicy.enabled(Map.of(), "REI_E2E_CODEX"));
    assertFalse(LiveE2EPolicy.enabled(Map.of("REI_E2E_CODEX", "1"), "REI_E2E_CODEX"));
    assertTrue(LiveE2EPolicy.enabled(Map.of("REI_E2E_CODEX", "true"), "REI_E2E_CODEX"));
  }
}
