package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Claudeコード拡張機能一覧連携の窓口の結果と状態の契約を検証するテスト。 */
class ClaudeCodeExtensionsAdapterTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void nativeStorageAliasIntoSourceNeverCreatesDirectoriesOrStartsCli(boolean missingBase)
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("project"));
    Path file = Files.writeString(root.resolve("A.txt"), "before\n");
    Path alias = temporary.resolve("alias");
    if (System.getProperty("os.name").startsWith("Windows")) {
      String script =
          "New-Item -ItemType Junction -Path '"
              + alias.toString().replace("'", "''")
              + "' -Target '"
              + root.toString().replace("'", "''")
              + "' | Out-Null";
      var process =
          new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes());
      assertEquals(0, process.waitFor(), output);
    } else Files.createSymbolicLink(alias, root);
    var properties = new ClaudeCodeProperties();
    properties.setEnabled(true);
    properties.setPersistSessions(true);
    properties.setNativeSessionDirectory(missingBase ? alias.resolve("sessions") : alias);
    var adapter =
        new ClaudeCodeExternalAgentExecutor(
            properties,
            new ExternalAgentProcessRunner() {
              /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
              @Override
              public Output run(
                  List<String> command,
                  Path directory,
                  String input,
                  Duration total,
                  Duration idle,
                  int bytes,
                  java.util.function.BooleanSupplier cancelled) {
                throw new AssertionError(
                    "Source storage alias must be rejected before CLI probing");
              }
            });
    var result =
        adapter.execute(
            new ExternalAgentRequest(
                ExternalAgentRequest.Agent.CLAUDE,
                ExternalAgentRequest.Action.REVIEW,
                "review",
                root,
                file,
                "",
                "run",
                "request"),
            () -> false);
    assertFalse(result.success());
    try (var files = Files.list(root)) {
      assertEquals(
          List.of("A.txt"), files.map(path -> path.getFileName().toString()).sorted().toList());
    }
    assertEquals("before\n", Files.readString(file));
  }

  /** 外部エージェントへの委譲で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void persistedSessionResumesWithToolFreeSubscriptionBoundaryAndConfirmedNativeUuid()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("project"));
    Path file = Files.writeString(root.resolve("A.txt"), "before\n");
    var properties = new ClaudeCodeProperties();
    properties.setEnabled(true);
    properties.setPersistSessions(true);
    properties.setNativeSessionDirectory(temporary.resolve("sessions"));
    var cwd = new ArrayList<Path>();
    var ids = new ArrayList<String>();
    var calls = new AtomicInteger();
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              Path directory,
              String input,
              Duration total,
              Duration idle,
              int bytes,
              java.util.function.BooleanSupplier cancelled) {
            if (command.contains("--version")) return output("2.1.286 (Claude Code)");
            if (command.contains("--help")) return output("supported");
            if (command.contains("auth")) return output("{\"authMethod\":\"oauth_token\"}");
            assertTrue(command.contains("--safe-mode"));
            assertEquals("", command.get(command.indexOf("--tools") + 1));
            assertTrue(command.contains("--strict-mcp-config"));
            assertFalse(command.contains("--no-session-persistence"));
            String id =
                command.contains("--resume")
                    ? command.get(command.indexOf("--resume") + 1)
                    : command.get(command.indexOf("--session-id") + 1);
            ids.add(id);
            cwd.add(directory);
            calls.incrementAndGet();
            return output(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\""
                    + id
                    + "\",\"structured_output\":{\"summary\":\"ok\",\"findings\":[],\"warnings\":[]}}");
          }
        };
    var adapter = new ClaudeCodeExternalAgentExecutor(properties, runner);
    var first =
        adapter.execute(
            new ExternalAgentRequest(
                ExternalAgentRequest.Agent.CLAUDE,
                ExternalAgentRequest.Action.REVIEW,
                "review",
                root,
                file,
                "",
                "run1",
                "one"),
            () -> false);
    assertTrue(first.success(), first.summary());
    assertNotNull(first.externalSessionId());
    assertTrue(Files.isDirectory(cwd.getFirst()));
    var second =
        adapter.execute(
            new ExternalAgentRequest(
                ExternalAgentRequest.Agent.CLAUDE,
                ExternalAgentRequest.Action.REVIEW,
                "continue",
                root,
                file,
                "",
                "run2",
                "two",
                first.externalSessionId()),
            () -> false);
    assertTrue(second.success(), second.summary());
    assertEquals(first.externalSessionId(), second.externalSessionId());
    assertEquals(cwd.getFirst(), cwd.getLast());
    assertEquals(2, calls.get());
    Path foreign = Files.createDirectory(temporary.resolve("foreign"));
    Path foreignFile = Files.writeString(foreign.resolve("A.txt"), "before\n");
    assertFalse(
        adapter
            .execute(
                new ExternalAgentRequest(
                    ExternalAgentRequest.Agent.CLAUDE,
                    ExternalAgentRequest.Action.REVIEW,
                    "continue",
                    foreign,
                    foreignFile,
                    "",
                    "run3",
                    "three",
                    first.externalSessionId()),
                () -> false)
            .success());
    assertEquals(2, calls.get());
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void fixProposalIsStructuredWithoutApplyingAndUnavailableSessionNeverStartsCli()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("A.txt"), "before\n");
    var paid = new AtomicInteger();
    var properties = new ClaudeCodeProperties();
    properties.setEnabled(true);
    properties.setFixProposalsEnabled(true);
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              Path directory,
              String input,
              Duration total,
              Duration idle,
              int bytes,
              java.util.function.BooleanSupplier cancelled) {
            if (command.contains("--version")) return output("2.1.286 (Claude Code)");
            if (command.contains("--help")) return output("supported");
            if (command.contains("auth")) return output("{\"authMethod\":\"claude.ai\"}");
            paid.incrementAndGet();
            assertTrue(command.get(command.indexOf("--json-schema") + 1).contains("expectedText"));
            return output(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":{\"summary\":\"proposal\",\"findings\":[],\"warnings\":[],\"proposal\":{\"path\":\"A.txt\",\"expectedText\":\"before\\n\",\"replacement\":\"after\\n\"}}}");
          }
        };
    var adapter = new ClaudeCodeExternalAgentExecutor(properties, runner);
    var fix =
        adapter.execute(
            new ExternalAgentRequest(
                ExternalAgentRequest.Agent.CLAUDE,
                ExternalAgentRequest.Action.PROPOSE_FIX,
                "propose",
                root,
                file,
                "",
                "run",
                "one"),
            () -> false);
    assertTrue(fix.success(), fix.summary());
    assertNotNull(fix.proposedChange());
    assertEquals("before\n", Files.readString(file));
    assertEquals("after\n", fix.proposedChange().replacement());
    assertEquals(
        ExternalAgentResult.Status.REJECTED,
        adapter
            .execute(
                new ExternalAgentRequest(
                    ExternalAgentRequest.Agent.CLAUDE,
                    ExternalAgentRequest.Action.REVIEW,
                    "resume",
                    root,
                    file,
                    "",
                    "other",
                    "two",
                    UUID.randomUUID().toString()),
                () -> false)
            .status());
    assertEquals(1, paid.get());
  }

  /** {@code output} の条件を再現するための補助情報を返す。 */
  static ExternalAgentProcessRunner.Output output(String value) {
    return new ExternalAgentProcessRunner.Output(
        ExternalAgentResult.Status.SUCCESS, value, "", 0, 1, false);
  }
}
