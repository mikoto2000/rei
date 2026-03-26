package dev.mikoto2000.rei.core.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * ベクトルストアの保存先パスを解決するユーティリティです。
 * <p>
 * Windows では {@code LOCALAPPDATA}、Linux などの Unix 系 OS では
 * {@code XDG_CACHE_HOME} を優先し、未設定の場合はユーザーホーム配下の一般的な
 * キャッシュディレクトリへフォールバックします。
 * </p>
 */
public final class VectorStorePaths {

  private VectorStorePaths() {
  }

  /**
   * 現在の実行環境に応じたベクトルストア保存先ファイルを返します。
   *
   * @return ベクトルストアの保存先ファイルパス
   */
  public static Path storeFile() {
    return storeFile(
        System.getProperty("os.name"),
        System.getProperty("user.home"),
        System.getenv("XDG_CACHE_HOME"),
        System.getenv("LOCALAPPDATA"));
  }

  /**
   * 指定された OS 名と環境変数相当の値からベクトルストア保存先ファイルを解決します。
   * テストから利用しやすいように、実行環境への直接依存を切り出しています。
   *
   * @param osName OS 名
   * @param userHome ユーザーホームディレクトリ
   * @param xdgCacheHome XDG キャッシュディレクトリ
   * @param localAppData Windows のローカルアプリケーションデータディレクトリ
   * @return ベクトルストアの保存先ファイルパス
   */
  static Path storeFile(String osName, String userHome, String xdgCacheHome, String localAppData) {
    if (isWindows(osName)) {
      return windowsStoreFile(userHome, localAppData);
    }
    return linuxStoreFile(userHome, xdgCacheHome);
  }

  /**
   * ベクトルストア保存先の親ディレクトリを作成します。
   *
   * @throws IOException ディレクトリ作成に失敗した場合
   */
  public static void createParentDirectories() throws IOException {
    Files.createDirectories(storeFile().getParent());
  }

  private static boolean isWindows(String osName) {
    return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
  }

  private static Path linuxStoreFile(String userHome, String xdgCacheHome) {
    Path baseDir = isBlank(xdgCacheHome)
        ? Path.of(userHome, ".cache")
        : Path.of(xdgCacheHome);
    return baseDir.resolve("rei").resolve("vector-store.json");
  }

  private static Path windowsStoreFile(String userHome, String localAppData) {
    Path baseDir = isBlank(localAppData)
        ? Path.of(userHome, "AppData", "Local")
        : Path.of(localAppData);
    return baseDir.resolve("rei").resolve("vector-store.json");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
