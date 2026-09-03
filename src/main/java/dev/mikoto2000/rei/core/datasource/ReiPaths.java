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
  private static final Path STARTUP_DIRECTORY = Path.of("").toAbsolutePath().normalize();

  private ReiPaths() {
  }

  public static Path startupDirectory() {
    return STARTUP_DIRECTORY;
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

  public static Path memoryConsolidationDbPath() {
    return memoryConsolidationDbPath(workDirectory());
  }

  public static Path curiosityDbPath() {
    return curiosityDbPath(workDirectory());
  }

  /**
   * 現在の実行環境に応じた履歴ファイルの保存先を返します。
   *
   * @return 履歴ファイルの保存先パス
   */
  public static Path historyFilePath() {
    return historyFilePath(workDirectory());
  }

  public static Path conversationLogsDirectory() {
    return conversationLogsDirectory(workDirectory());
  }

  public static Path profileLogPath() {
    return profileLogPath(workDirectory());
  }

  /**
   * 現在の実行環境に応じた外部設定ファイルの保存先を返します。
   *
   * @return 外部設定ファイルの保存先パス
   */
  public static Path configFilePath() {
    return configFilePath(workDirectory());
  }

  public static Path additionalSystemPromptFilePath() {
    return additionalSystemPromptFilePath(workDirectory());
  }

  public static Path projectsFilePath() {
    return projectsFilePath(startupDirectory());
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
    return startupDirectory();
  }

  public static Path memoryDbPath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("memory.db");
  }

  public static Path vectorStoreDbPath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("vectorstore.db");
  }

  public static Path memoryConsolidationDbPath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("memory-consolidation.db");
  }

  public static Path curiosityDbPath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("curiosity.db");
  }

  public static Path historyFilePath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("history");
  }

  public static Path conversationLogsDirectory(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("conversation-logs");
  }

  public static Path profileLogPath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("profile.log");
  }

  public static Path configFilePath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("application.yaml");
  }

  public static Path additionalSystemPromptFilePath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("additional-system-prompt.md");
  }

  public static Path projectsFilePath(Path workDirectory) {
    return workDirectory.resolve(".rei").resolve("projects");
  }
}
