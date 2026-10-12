package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;

class FileLineCountsTest {
  @TempDir Path root;

  @Test void countsLfLikeWcAndReportsIndividualFailures() throws Exception {
    Files.writeString(root.resolve("日本語.md"), "一\r\n二\n末尾");
    Files.writeString(root.resolve("empty.md"), "");
    Files.writeString(root.resolve("unterminated.md"), "one line");
    var result = new Tools().countFileLines(
        List.of("日本語.md", "empty.md", "unterminated.md", "missing.md", "."), root);
    assertEquals(2L, result.totalLines());
    assertEquals(2L, result.files().get(0).lineCount());
    assertEquals(0L, result.files().get(1).lineCount());
    assertEquals(0L, result.files().get(2).lineCount());
    assertNull(result.files().get(3).lineCount());
    assertNotNull(result.files().get(3).error());
    assertNotNull(result.files().get(4).error());
  }

  @Test void validatesBatchAndExposesReadOnlyTool() {
    var tools = new Tools();
    assertThrows(IllegalArgumentException.class, () -> tools.countFileLines(List.of(), root));
    assertThrows(IllegalArgumentException.class, () -> tools.countFileLines(List.of(" "), root));
    assertThrows(IllegalArgumentException.class, () -> tools.countFileLines(java.util.Collections.nCopies(65, "x"), root));
    assertEquals(java.util.Set.of(ActionCapability.READ), ToolPermissionPolicy.intrinsicCapabilities("countFileLines"));
    assertTrue(java.util.Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
        .anyMatch(tool -> tool.getToolDefinition().name().equals("countFileLines")));
  }
}
