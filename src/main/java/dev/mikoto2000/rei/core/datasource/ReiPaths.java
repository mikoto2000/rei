package dev.mikoto2000.rei.core.datasource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Rei が利用する SQLite データベースの保存先パスを解決するユーティリティです。
 * <p>
 * Windows では {@code LOCALAPPDATA}、Linux などの Unix 系 OS では
 * {@code XDG_CACHE_HOME} を優先し、未設定の場合はユーザーホーム配下の一般的な
 * キャッシュディレクトリへフォールバックします。
 * </p>
 */
public final class ReiPaths {

  private ReiPaths() {
  }

  /**
   * 現在の実行環境に応じた SQLite データベースファイルの保存先を返します。
   *
   * @return SQLite データベースファイルの保存先パス
   */
  public static Path memoryDbPath() {
    return memoryDbPath(
        System.getProperty("os.name"),
        System.getProperty("user.home"),
        System.getenv("XDG_CACHE_HOME"),
        System.getenv("LOCALAPPDATA"));
  }

  /**
   * 指定された OS 名と環境変数相当の値から SQLite データベースの保存先を解決します。
   * テストから利用しやすいように、実行環境への直接依存を切り出しています。
   *
   * @param osName OS 名
   * @param userHome ユーザーホームディレクトリ
   * @param xdgCacheHome XDG キャッシュディレクトリ
   * @param localAppData Windows のローカルアプリケーションデータディレクトリ
   * @return SQLite データベースファイルの保存先パス
   */
  static Path memoryDbPath(String osName, String userHome, String xdgCacheHome, String localAppData) {
    if (isWindows(osName)) {
      return windowsMemoryDbPath(userHome, localAppData);
    }
    return linuxMemoryDbPath(userHome, xdgCacheHome);
  }

  /**
   * 指定したファイルパスの親ディレクトリを作成します。
   *
   * @param filePath 親ディレクトリを作成したいファイルパス
   * @throws Exception ディレクトリ作成に失敗した場合
   */
  public static void ensureParentDirectoryExists(Path filePath) throws Exception {
    Files.createDirectories(filePath.getParent());
  }

  private static boolean isWindows(String osName) {
    return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
  }

  private static Path linuxMemoryDbPath(String userHome, String xdgCacheHome) {
    Path baseDir = isBlank(xdgCacheHome)
        ? Path.of(userHome, ".cache")
        : Path.of(xdgCacheHome);
    return baseDir.resolve("rei").resolve("memory.db");
  }

  private static Path windowsMemoryDbPath(String userHome, String localAppData) {
    Path baseDir = isBlank(localAppData)
        ? Path.of(userHome, "AppData", "Local")
        : Path.of(localAppData);
    return baseDir.resolve("rei").resolve("memory.db");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
