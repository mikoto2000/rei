package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクト追加ディレクトリ完了の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectAddDirectoryCompletionTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completesAbsoluteDirectoryFragment() throws Exception {
    Path target = tempDir.resolve("project-alpha");
    java.nio.file.Files.createDirectory(target);
    java.nio.file.Files.createDirectory(tempDir.resolve("other"));

    String fragment = tempDir.resolve("proj").toString();

    assertThat(ProjectAddDirectoryCompletion.complete("/project add " + fragment, tempDir))
        .containsExactly(target.toString());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completesRelativeDirectoryFragmentFromCurrentProject() throws Exception {
    Path target = tempDir.resolve("src-main");
    java.nio.file.Files.createDirectory(target);
    java.nio.file.Files.createDirectory(tempDir.resolve("build"));

    assertThat(ProjectAddDirectoryCompletion.complete("/project add src", tempDir))
        .containsExactly(target.toString());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void quotesCandidateWhenInputIsQuoted() throws Exception {
    Path target = tempDir.resolve("project space");
    java.nio.file.Files.createDirectory(target);

    String fragment = tempDir.resolve("project").toString();

    assertThat(ProjectAddDirectoryCompletion.complete("/project add \"" + fragment, tempDir))
        .containsExactly("\"" + target + "\"");
  }
}
