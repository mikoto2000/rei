package dev.mikoto2000.rei.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** バックエンド動作モードの結果と状態の契約を検証するテスト。 */
class BackendModeTest {
  /** 旧形式既定を保持することを確認する。 */
  @Test
  void preservesLegacyDefaultDuringMigration() {
    assertEquals(BackendMode.LEGACY_SHELL, BackendMode.parse(new String[0]));
    assertEquals(BackendMode.SERVER, BackendMode.parse(new String[] {"--mode=server"}));
    assertEquals(
        BackendMode.LEGACY_SHELL,
        BackendMode.parse(new String[] {"--mode", "legacy-shell", "-p", "."}));
  }

  /** 起動環境の準備で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void refusesAmbiguousOrUnimplementedModesBeforeSpring() {
    for (String[] args :
        new String[][] {
          {"--mode=auto"},
          {"--mode=client"},
          {"--mode"},
          {"--mode=server", "--mode=legacy-shell"},
          {"--mode=unknown"}
        }) assertThrows(IllegalArgumentException.class, () -> BackendMode.parse(args));
  }
}
