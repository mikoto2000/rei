package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PlatformDetectorTest {

  private final PlatformDetector detector = new PlatformDetector();

  @Test
  void detectsLinuxX8664() {
    assertEquals(SqliteVecPlatform.LINUX_X86_64, detector.detect("Linux", "amd64"));
  }

  @Test
  void detectsLinuxAarch64() {
    assertEquals(SqliteVecPlatform.LINUX_AARCH64, detector.detect("Linux", "aarch64"));
  }

  @Test
  void detectsMacOsAarch64() {
    assertEquals(SqliteVecPlatform.MACOS_AARCH64, detector.detect("Mac OS X", "aarch64"));
  }

  @Test
  void detectsWindowsX8664() {
    assertEquals(SqliteVecPlatform.WINDOWS_X86_64, detector.detect("Windows 11", "amd64"));
  }

  @Test
  void rejectsUnsupportedPlatforms() {
    assertThrows(IllegalStateException.class, () -> detector.detect("FreeBSD", "amd64"));
  }
}
