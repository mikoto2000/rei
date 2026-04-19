package dev.mikoto2000.rei.core.datasource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rei が利用する作業用ファイルの保存先パスを解決するユーティリティです。
 * <p>
 * すべての永続ファイルは、起動時のカレントディレクトリ配下にある
 * {@code .rei} ディレクトリへ保存します。
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
    return memoryDbPath(workDirectory());
  }

  /**
   * 現在の実行環境に応じたベクトルストア用 SQLite データベースファイルの保存先を返します。
   *
   * @return ベクトルストア用 SQLite データベースファイルの保存先パス
   */
  public static Path vectorStoreDbPath() {
    return vectorStoreDbPath(workDirectory());
  }

  /**
   * 現在の実行環境に応じた履歴ファイルの保存先を返します。
   *
   * @return 履歴ファイルの保存先パス
   */
  public static Path historyFilePath() {
    return historyFilePath(workDirectory());
  }

  /**
   * 現在の実行環境に応じた外部設定ファイルの保存先を返します。
   *
   * @return 外部設定ファイルの保存先パス
   */
  public static Path configFilePath() {
    return configFilePath(workDirectory());
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

  static Path workDirectory() {
    return Path.of("").toAbsolutePath().normalize();
  }

  public static Path memoryDbPath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("memory.db");
  }

  public static Path vectorStoreDbPath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("vectorstore.db");
  }

  public static Path historyFilePath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("history");
  }

  public static Path configFilePath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("application.yaml");
  }
}
