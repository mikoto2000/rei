package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpToolPermissionTest {
  @Test
  void mcpFixedReadAllowlistExcludesPersonalMemoryPdfPathsAndProcessLogs() {
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null)),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {});
    var owner =
        new AgentRunContext(
            "mcp",
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.MCP,
            AgentRunContext.Mode.READ_ONLY);
    assertDoesNotThrow(() -> guard.check("readMultiFile", "{}", owner));
    assertDoesNotThrow(() -> guard.check("webSearch", "{}", owner));
    for (String tool :
        List.of(
            "readPdfFile",
            "searchMemoryHistory",
            "getShellProcessStatus",
            "writeMultiFile",
            "executeShellCommand"))
      assertThrows(ToolPermissionException.class, () -> guard.check(tool, "{}", owner), tool);
    var escalated =
        new AgentRunContext(
            "mcp",
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.MCP,
            AgentRunContext.Mode.EXCLUSIVE);
    assertThrows(
        ToolPermissionException.class, () -> guard.check("readMultiFile", "{}", escalated));
  }
}
