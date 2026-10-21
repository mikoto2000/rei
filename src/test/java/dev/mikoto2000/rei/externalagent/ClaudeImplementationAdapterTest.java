package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Claude実装連携の窓口の結果と状態の契約を検証するテスト。 */
class ClaudeImplementationAdapterTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void implementationIsToolFreeEphemeralAndReturnsOnlyStructuredChanges() throws Exception {
    Path file = Files.writeString(root.resolve("A.txt"), "before\n");
    String hash = ImplementationProposal.sha256(Files.readAllBytes(file));
    var properties = new ClaudeCodeProperties();
    properties.setEnabled(true);
    properties.setImplementationEnabled(true);
    properties.setPersistSessions(true);
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              Path cwd,
              String input,
              Duration total,
              Duration idle,
              int bytes,
              java.util.function.BooleanSupplier cancelled) {
            if (command.contains("--version"))
              return ClaudeCodeExtensionsAdapterTest.output("2.1.286 (Claude Code)");
            if (command.contains("--help"))
              return ClaudeCodeExtensionsAdapterTest.output("supported");
            if (command.contains("auth"))
              return ClaudeCodeExtensionsAdapterTest.output("{\"authMethod\":\"claude.ai\"}");
            assertEquals("", command.get(command.indexOf("--tools") + 1));
            assertTrue(command.contains("--no-session-persistence"));
            assertFalse(command.contains("--resume"));
            assertTrue(
                command.get(command.indexOf("--json-schema") + 1).contains("expectedSha256"));
            assertTrue(input.contains(hash));
            return ClaudeCodeExtensionsAdapterTest.output(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":{\"summary\":\"proposal\",\"findings\":[],\"warnings\":[],\"implementation\":{\"edits\":[{\"path\":\"A.txt\",\"expectedSha256\":\""
                    + hash
                    + "\",\"replacement\":\"after\\n\"}]}}}");
          }
        };
    var result =
        new ClaudeCodeExternalAgentExecutor(properties, runner)
            .execute(
                new ExternalAgentRequest(
                    ExternalAgentRequest.Agent.CLAUDE,
                    ExternalAgentRequest.Action.IMPLEMENT,
                    "implement",
                    root,
                    file,
                    "",
                    "run",
                    "id"),
                () -> false);
    assertTrue(result.success(), result.summary());
    assertNotNull(result.implementation());
    assertNull(result.externalSessionId());
    assertEquals("before\n", Files.readString(file));
  }
}
