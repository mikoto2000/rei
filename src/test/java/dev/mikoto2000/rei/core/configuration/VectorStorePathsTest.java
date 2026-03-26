package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VectorStorePathsTest {

  @Test
  void storeFileUsesXdgCacheHomeOnLinux() {
    Path expected = Path.of(
        "/tmp/cache",
        "rei",
        "vector-store.json");

    assertEquals(expected, VectorStorePaths.storeFile("Linux", "/home/alice", "/tmp/cache", null));
  }

  @Test
  void storeFileFallsBackToUserHomeCacheOnLinux() {
    Path expected = Path.of(
        "/home/alice",
        ".cache",
        "rei",
        "vector-store.json");

    assertEquals(expected, VectorStorePaths.storeFile("Linux", "/home/alice", null, null));
  }

  @Test
  void storeFileUsesLocalAppDataOnWindows() {
    Path expected = Path.of(
        "C:/Users/Alice/AppData/Local",
        "rei",
        "vector-store.json");

    assertEquals(expected, VectorStorePaths.storeFile("Windows 11", "C:/Users/Alice", null,
        "C:/Users/Alice/AppData/Local"));
  }

  @Test
  void storeFileFallsBackToUserHomeAppDataOnWindows() {
    Path expected = Path.of(
        "C:/Users/Alice",
        "AppData",
        "Local",
        "rei",
        "vector-store.json");

    assertEquals(expected, VectorStorePaths.storeFile("Windows 11", "C:/Users/Alice", null, null));
  }
}
