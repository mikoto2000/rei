package dev.mikoto2000.rei.core.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ReiPathsTest {

  @Test
  void memoryDbPathUsesXdgCacheHomeOnLinux() {
    Path expected = Path.of(
        "/tmp/cache",
        "rei",
        "memory.db");

    assertEquals(expected, ReiPaths.memoryDbPath("Linux", "/home/alice", "/tmp/cache", null));
  }

  @Test
  void memoryDbPathFallsBackToUserHomeCacheOnLinux() {
    Path expected = Path.of(
        "/home/alice",
        ".cache",
        "rei",
        "memory.db");

    assertEquals(expected, ReiPaths.memoryDbPath("Linux", "/home/alice", null, null));
  }

  @Test
  void memoryDbPathUsesLocalAppDataOnWindows() {
    Path expected = Path.of(
        "C:/Users/Alice/AppData/Local",
        "rei",
        "memory.db");

    assertEquals(expected, ReiPaths.memoryDbPath("Windows 11", "C:/Users/Alice", null,
        "C:/Users/Alice/AppData/Local"));
  }

  @Test
  void memoryDbPathFallsBackToUserHomeAppDataOnWindows() {
    Path expected = Path.of(
        "C:/Users/Alice",
        "AppData",
        "Local",
        "rei",
        "memory.db");

    assertEquals(expected, ReiPaths.memoryDbPath("Windows 11", "C:/Users/Alice", null, null));
  }
}
