package dev.mikoto2000.rei.core.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** れいパス一覧の結果と状態の契約を検証するテスト。 */
class ReiPathsTest {

  /** 記憶データベースパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void memoryDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "memory.db");

    assertEquals(expected, ReiPaths.memoryDbPath(workDirectory));
  }

  /** ベクトルについて、データベースパス利用する作業ディレクトリを保存することを確認する。 */
  @Test
  void vectorStoreDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "vectorstore.db");

    assertEquals(expected, ReiPaths.vectorStoreDbPath(workDirectory));
  }

  /** 記憶統合データベースパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void memoryConsolidationDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "memory-consolidation.db");

    assertEquals(expected, ReiPaths.memoryConsolidationDbPath(workDirectory));
  }

  /** 情報への関心データベースパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void curiosityDbPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "curiosity.db");

    assertEquals(expected, ReiPaths.curiosityDbPath(workDirectory));
  }

  /** 履歴ファイルパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void historyFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "history");

    assertEquals(expected, ReiPaths.historyFilePath(workDirectory));
  }

  /** 会話ログ一覧ディレクトリについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void conversationLogsDirectoryUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");

    assertEquals(
        Path.of("/work/rei", "conversation-logs"),
        ReiPaths.conversationLogsDirectory(workDirectory));
  }

  /** 利用者情報ログパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void profileLogPathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");

    assertEquals(Path.of("/work/rei", "profile.log"), ReiPaths.profileLogPath(workDirectory));
  }

  /** 設定ファイルパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void configFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "application.yaml");

    assertEquals(expected, ReiPaths.configFilePath(workDirectory));
  }

  /** 追加システムへの指示ファイルパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void additionalSystemPromptFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "additional-system-prompt.md");

    assertEquals(expected, ReiPaths.additionalSystemPromptFilePath(workDirectory));
  }

  /** プロジェクト一覧ファイルパスについて、作業ディレクトリを利用することを確認する。 */
  @Test
  void projectsFilePathUsesWorkingDirectory() {
    Path workDirectory = Path.of("/work/rei");
    Path expected = Path.of("/work/rei", "projects.json");

    assertEquals(expected, ReiPaths.projectsFilePath(workDirectory));
  }
}
