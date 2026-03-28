package dev.mikoto2000.rei.core.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * ベクトルストアの保存先パスを解決するユーティリティです。
 */
public final class VectorStorePaths {

  private VectorStorePaths() {
  }

  public static Path storeFile() {
    return storeFile(
        System.getProperty("os.name"),
        System.getProperty("user.home"),
        System.getenv("XDG_CACHE_HOME"),
        System.getenv("LOCALAPPDATA"));
  }

  public static Path documentIndexFile() {
    return documentIndexFile(
        System.getProperty("os.name"),
        System.getProperty("user.home"),
        System.getenv("XDG_CACHE_HOME"),
        System.getenv("LOCALAPPDATA"));
  }

  static Path storeFile(String osName, String userHome, String xdgCacheHome, String localAppData) {
    return baseDir(osName, userHome, xdgCacheHome, localAppData).resolve("rei").resolve("vector-store.json");
  }

  static Path documentIndexFile(String osName, String userHome, String xdgCacheHome, String localAppData) {
    return baseDir(osName, userHome, xdgCacheHome, localAppData).resolve("rei").resolve("vector-documents.json");
  }

  public static void createParentDirectories() throws IOException {
    Files.createDirectories(storeFile().getParent());
  }

  private static boolean isWindows(String osName) {
    return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
  }

  private static Path baseDir(String osName, String userHome, String xdgCacheHome, String localAppData) {
    if (isWindows(osName)) {
      return isBlank(localAppData)
          ? Path.of(userHome, "AppData", "Local")
          : Path.of(localAppData);
    }
    return isBlank(xdgCacheHome)
        ? Path.of(userHome, ".cache")
        : Path.of(xdgCacheHome);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
