package dev.mikoto2000.rei.terminal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 起動選択可能な設定の結果と状態の契約を検証するテスト。 */
class LauncherOptionsTest {
  /** 軽量CLIの起動で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void defaultsToAutoAndRejectsUnknownModesBeforeBackendStarts() {
    assertEquals("auto", LauncherOptions.parse(new String[0]).mode());
    assertThrows(
        IllegalArgumentException.class,
        () -> LauncherOptions.parse(new String[] {"--mode", "unknown"}));
    assertThrows(
        IllegalArgumentException.class,
        () -> LauncherOptions.parse(new String[] {"--mode=client", "--mode=auto"}));
  }

  /** サーバーについて、必要とする明示的な確認を停止することを確認する。 */
  @Test
  void serverStopRequiresExplicitConfirmation() {
    assertFalse(LauncherOptions.parse(new String[] {"server", "stop"}).confirmed());
    assertTrue(LauncherOptions.parse(new String[] {"server", "stop", "--yes"}).confirmed());
  }
}
