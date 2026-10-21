package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** ファイル行件数の結果と状態の契約を検証するテスト。 */
class FileLineCountsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 共通の実行基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void countsLfLikeWcAndReportsIndividualFailures() throws Exception {
    Files.writeString(root.resolve("日本語.md"), "一\r\n二\n末尾");
    Files.writeString(root.resolve("empty.md"), "");
    Files.writeString(root.resolve("unterminated.md"), "one line");
    var result =
        new Tools()
            .countFileLines(
                List.of("日本語.md", "empty.md", "unterminated.md", "missing.md", "."), root);
    assertEquals(2L, result.totalLines());
    assertEquals(2L, result.files().get(0).lineCount());
    assertEquals(0L, result.files().get(1).lineCount());
    assertEquals(0L, result.files().get(2).lineCount());
    assertNull(result.files().get(3).lineCount());
    assertNotNull(result.files().get(3).error());
    assertNotNull(result.files().get(4).error());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void validatesBatchAndExposesReadOnlyTool() {
    var tools = new Tools();
    assertThrows(IllegalArgumentException.class, () -> tools.countFileLines(List.of(), root));
    assertThrows(IllegalArgumentException.class, () -> tools.countFileLines(List.of(" "), root));
    assertThrows(
        IllegalArgumentException.class,
        () -> tools.countFileLines(java.util.Collections.nCopies(65, "x"), root));
    assertEquals(
        java.util.Set.of(ActionCapability.READ),
        ToolPermissionPolicy.intrinsicCapabilities("countFileLines"));
    assertTrue(
        java.util.Arrays.stream(
                MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
            .anyMatch(tool -> tool.getToolDefinition().name().equals("countFileLines")));
  }
}
