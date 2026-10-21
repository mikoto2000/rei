package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MCPファイル状態の記録の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class McpFileSnapshotTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path other;

  /** 共通の実行基盤で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void mcpCannotReadReiDataCredentialFilesOrAnotherCapturedProject() throws Exception {
    Files.writeString(root.resolve("App.java"), "class App {}");
    var privateDirectory = Files.createDirectory(root.resolve("runtime-data"));
    Files.writeString(privateDirectory.resolve("config.json"), "private token");
    Files.createDirectory(root.resolve(".rei"));
    Files.writeString(root.resolve(".rei/token.json"), "private token");
    Files.writeString(other.resolve("other.txt"), "other project");
    Files.writeString(root.resolve("sample.db"), "private database");
    String old = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", privateDirectory.toString());
    try {
      var owner =
          new AgentRunContext(
              "mcp",
              "session",
              root,
              "project",
              AgentRunContext.RequestSource.MCP,
              AgentRunContext.Mode.READ_ONLY);
      try (var scope = AgentRunScope.open(owner)) {
        assertDoesNotThrow(() -> new FileSnapshots().get(root, Path.of("App.java")));
        assertThrows(
            java.io.IOException.class,
            () -> new FileSnapshots().get(root, Path.of(".rei/token.json")));
        assertThrows(
            java.io.IOException.class,
            () -> new FileSnapshots().get(root, Path.of("runtime-data/config.json")));
        assertThrows(
            java.io.IOException.class, () -> new FileSnapshots().get(other, Path.of("other.txt")));
        assertThrows(
            java.io.IOException.class, () -> new FileSnapshots().get(root, Path.of("sample.db")));
      }
      assertDoesNotThrow(
          () -> new FileSnapshots().get(root, Path.of(".rei/token.json")),
          "Existing explicit non-MCP file behavior remains intact");
    } finally {
      if (old == null) System.clearProperty("rei.data-dir");
      else System.setProperty("rei.data-dir", old);
    }
  }
}
