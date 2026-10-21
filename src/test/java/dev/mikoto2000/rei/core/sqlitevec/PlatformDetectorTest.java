package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 実行環境検出の窓口の結果と状態の契約を検証するテスト。 */
class PlatformDetectorTest {

  /** 検証に使う検出の窓口。 */
  private final PlatformDetector detector = new PlatformDetector();

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void detectsLinuxX8664() {
    assertEquals(SqliteVecPlatform.LINUX_X86_64, detector.detect("Linux", "amd64"));
  }

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void detectsLinuxAarch64() {
    assertEquals(SqliteVecPlatform.LINUX_AARCH64, detector.detect("Linux", "aarch64"));
  }

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void detectsMacOsAarch64() {
    assertEquals(SqliteVecPlatform.MACOS_AARCH64, detector.detect("Mac OS X", "aarch64"));
  }

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void detectsWindowsX8664() {
    assertEquals(SqliteVecPlatform.WINDOWS_X86_64, detector.detect("Windows 11", "amd64"));
  }

  /** 文書検索用の保存基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsUnsupportedPlatforms() {
    assertThrows(IllegalStateException.class, () -> detector.detect("FreeBSD", "amd64"));
  }
}
