package dev.mikoto2000.rei.core.datasource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rei が利用する作業用ファイルの保存先パスを解決するユーティリティです。
 *
 * <p>Rei 自身のデータは OS 標準のグローバルデータディレクトリへ保存します。 Path を受け取るオーバーロードは明示的に指定されたデータディレクトリを使用します。
 */
public final class ReiPaths {
  /** 起動時ディレクトリ。 */
  private static final Path STARTUP_DIRECTORY = Path.of("").toAbsolutePath().normalize();

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ReiPaths() {}

  /** データ保存先の利用で用いる起動時ディレクトリを返す。 */
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

  /** データ保存先の利用で用いる記憶統合データベースパスを返す。 */
  public static Path memoryConsolidationDbPath() {
    return memoryConsolidationDbPath(workDirectory());
  }

  /** データ保存先の利用で用いる情報への関心データベースパスを返す。 */
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

  /** データ保存先の利用で用いる会話ログ一覧ディレクトリを返す。 */
  public static Path conversationLogsDirectory() {
    return conversationLogsDirectory(workDirectory());
  }

  /** データ保存先の利用で用いる利用者情報ログパスを返す。 */
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

  /** データ保存先の利用で用いる追加システムへの指示ファイルパスを返す。 */
  public static Path additionalSystemPromptFilePath() {
    return additionalSystemPromptFilePath(workDirectory());
  }

  /** データ保存先の利用で用いるプロジェクト一覧ファイルパスを返す。 */
  public static Path projectsFilePath() {
    return projectsFilePath(workDirectory());
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

  /** データ保存先の利用で用いる作業ディレクトリを返す。 */
  static Path workDirectory() {
    return ReiDataDirectory.current();
  }

  /** データ保存先の利用で用いる記憶データベースパスを返す。 */
  public static Path memoryDbPath(Path workDirectory) {
    return workDirectory.resolve("memory.db");
  }

  /** データ保存先の利用で用いるベクトル保存先データベースパスを返す。 */
  public static Path vectorStoreDbPath(Path workDirectory) {
    return workDirectory.resolve("vectorstore.db");
  }

  /** データ保存先の利用で用いる記憶統合データベースパスを返す。 */
  public static Path memoryConsolidationDbPath(Path workDirectory) {
    return workDirectory.resolve("memory-consolidation.db");
  }

  /** データ保存先の利用で用いる情報への関心データベースパスを返す。 */
  public static Path curiosityDbPath(Path workDirectory) {
    return workDirectory.resolve("curiosity.db");
  }

  /** データ保存先の利用で用いる履歴ファイルパスを返す。 */
  public static Path historyFilePath(Path workDirectory) {
    return workDirectory.resolve("history");
  }

  /** データ保存先の利用で用いる会話ログ一覧ディレクトリを返す。 */
  public static Path conversationLogsDirectory(Path workDirectory) {
    return workDirectory.resolve("conversation-logs");
  }

  /** データ保存先の利用で用いる利用者情報ログパスを返す。 */
  public static Path profileLogPath(Path workDirectory) {
    return workDirectory.resolve("profile.log");
  }

  /** データ保存先の利用で用いる設定ファイルパスを返す。 */
  public static Path configFilePath(Path workDirectory) {
    return workDirectory.resolve("application.yaml");
  }

  /** データ保存先の利用で用いる追加システムへの指示ファイルパスを返す。 */
  public static Path additionalSystemPromptFilePath(Path workDirectory) {
    return workDirectory.resolve("additional-system-prompt.md");
  }

  /** データ保存先の利用で用いるプロジェクト一覧ファイルパスを返す。 */
  public static Path projectsFilePath(Path workDirectory) {
    return workDirectory.resolve("projects.json");
  }
}
