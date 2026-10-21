package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MultilanguageGit保有情報の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class MultilanguageGitInventoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code git} の条件を再現するため、検証用の操作や状態を扱う。 */
  void git(String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>(java.util.List.of("git", "-C", root.toString()));
    command.addAll(java.util.List.of(arguments));
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(
        0,
        process.exitValue(),
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void realGitInventoryNeverExecutesConfiguredFsmonitorAndReturnsMetadataOnly() throws Exception {
    git("init", "--quiet");
    Files.writeString(
        root.resolve("helper.ts"),
        "export function help() {}\nconst secret = 'PRIVATE_SOURCE_MARKER';\n");
    git("add", "helper.ts");
    Path hook = root.resolve(".git/fsmonitor-fixture.sh");
    Files.writeString(hook, "#!/bin/sh\nprintf called > fsmonitor-called.txt\nexit 0\n");
    hook.toFile().setExecutable(true);
    git("config", "core.fsmonitor", hook.toString().replace('\\', '/'));
    var view = new RepositoryMapService().map(root, "", 100);
    assertEquals("TYPESCRIPT", view.items().getFirst().language());
    assertFalse(view.toString().contains("PRIVATE_SOURCE_MARKER"));
    assertFalse(Files.exists(root.resolve("fsmonitor-called.txt")));
    git("ls-files", "--cached", "--others", "--exclude-standard");
    assertTrue(
        Files.exists(root.resolve("fsmonitor-called.txt")),
        "The fixture must prove the configured hook can actually run");
  }
}
