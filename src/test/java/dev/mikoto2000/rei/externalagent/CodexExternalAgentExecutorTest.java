package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Codex外部エージェント実行先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class CodexExternalAgentExecutorTest {
  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void nonzeroExitExtractsJsonErrorWithoutForwardingRawEvents() {
    var result =
        new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
            .parse(
                new ExternalAgentProcessRunner.Output(
                    ExternalAgentResult.Status.FAILED,
                    "{\"type\":\"turn.failed\",\"error\":{\"message\":\"Model unavailable token=do-not-display\"}}",
                    "",
                    1,
                    1,
                    false));
    assertTrue(result.summary().contains("Model unavailable"));
    assertFalse(result.summary().contains("do-not-display"));
    assertFalse(result.summary().contains("turn.failed"));
  }

  /** 外部エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void nonzeroExitIncludesBoundedRedactedDiagnostic() {
    var result =
        new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
            .parse(
                new ExternalAgentProcessRunner.Output(
                    ExternalAgentResult.Status.FAILED,
                    "",
                    "Error loading config.toml: invalid permission profile token=do-not-display "
                        + "x".repeat(4000),
                    1,
                    1,
                    false));
    assertTrue(result.summary().contains("invalid permission profile"));
    assertFalse(result.summary().contains("do-not-display"));
    assertTrue(result.summary().length() < 1200);
  }

  /** 判定期間について、OSの機能実行可能なを利用することを確認する。 */
  @Test
  void windowsUsesNativeExecutableAndPreservesExplicitConfiguration() {
    assertEquals(
        "codex.exe", CodexExternalAgentExecutor.resolveCommand("codex", "Windows 11", List.of()));
    assertEquals("codex", CodexExternalAgentExecutor.resolveCommand("codex", "Linux", List.of()));
    assertEquals("codex", CodexExternalAgentExecutor.resolveCommand("codex", "Darwin", List.of()));
    assertEquals(
        "C:\\Tools\\codex.exe",
        CodexExternalAgentExecutor.resolveCommand("C:\\Tools\\codex.exe", "Windows 11", List.of()));
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void resolvesNpmNativeBinaryWithoutInvokingShellAndPrefersPathExecutable() throws Exception {
    var npm = java.nio.file.Files.createDirectories(root.resolve("npm with spaces"));
    boolean arm = System.getProperty("os.arch", "").equals("aarch64");
    var binary =
        npm.resolve(
            "node_modules/@openai/codex/node_modules/@openai/codex-win32-"
                + (arm ? "arm64" : "x64")
                + "/vendor/"
                + (arm ? "aarch64" : "x86_64")
                + "-pc-windows-msvc/bin/codex.exe");
    java.nio.file.Files.createDirectories(binary.getParent());
    java.nio.file.Files.createFile(binary);
    java.nio.file.Files.createFile(npm.resolve("codex.cmd"));
    assertEquals(
        binary.toString(),
        CodexExternalAgentExecutor.resolveCommand("codex", "Windows 11", List.of(npm)));
    var nativeDirectory = java.nio.file.Files.createDirectories(root.resolve("native"));
    var nativeBinary = java.nio.file.Files.createFile(nativeDirectory.resolve("codex.exe"));
    assertEquals(
        nativeBinary.toString(),
        CodexExternalAgentExecutor.resolveCommand(
            "codex", "Windows 11", List.of(npm, nativeDirectory)));
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void startupFailureExplainsConfigurationAndOsError() {
    var result =
        new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
            .parse(
                new ExternalAgentProcessRunner.Output(
                    ExternalAgentResult.Status.UNAVAILABLE,
                    "",
                    "CreateProcess error=2 token=do-not-display",
                    null,
                    1,
                    false));
    assertTrue(result.summary().contains("rei.external-agents.codex.command"));
    assertTrue(result.summary().contains("codex.exe"));
    assertTrue(result.summary().contains("CreateProcess error=2"));
    assertFalse(result.summary().contains("do-not-display"));
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void authenticationFailureInJsonStdoutIsReportedWithoutLeakingText() {
    var result =
        new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
            .parse(
                new ExternalAgentProcessRunner.Output(
                    ExternalAgentResult.Status.FAILED,
                    "{\"error\":\"401 unauthorized\"}",
                    "",
                    1,
                    1,
                    false));
    assertEquals("Codex CLI authentication failed", result.summary());
  }

  /** 検証に使う作業ルート。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unsupportedCliFailsClosedWithoutStartingAReview() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              java.nio.file.Path cwd,
              String input,
              java.time.Duration total,
              java.time.Duration idle,
              int limit,
              java.util.function.BooleanSupplier cancelled) {
            calls.incrementAndGet();
            assertEquals(List.of("test-codex", "exec", "--help"), command);
            return new Output(
                ExternalAgentResult.Status.SUCCESS, "old CLI --sandbox", "", 0, 1, false);
          }
        };
    var request =
        new ExternalAgentRequest(
            ExternalAgentRequest.Agent.CODEX,
            ExternalAgentRequest.Action.REVIEW,
            "review",
            root,
            null,
            "",
            "run",
            "id");
    var properties = new CodexProperties();
    properties.setCommand("test-codex");
    assertEquals(
        ExternalAgentResult.Status.UNAVAILABLE,
        new CodexExternalAgentExecutor(properties, runner).execute(request, () -> false).status());
    assertEquals(1, calls.get());
  }

  /** 標準入力データ形式を利用することを確認する。 */
  @Test
  void capableCliUsesStdinSchemaAndCanonicalCwdAndRemovesTemporarySchema() {
    var schema = new java.util.concurrent.atomic.AtomicReference<java.nio.file.Path>();
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              java.nio.file.Path cwd,
              String input,
              java.time.Duration total,
              java.time.Duration idle,
              int limit,
              java.util.function.BooleanSupplier cancelled) {
            if (command.contains("--help"))
              return new Output(
                  ExternalAgentResult.Status.SUCCESS,
                  "--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json",
                  "",
                  0,
                  1,
                  false);
            assertEquals(root, cwd);
            assertTrue(input.contains("untrusted input"));
            assertTrue(input.contains("Do not run git commit"));
            assertTrue(input.contains("design context"));
            schema.set(java.nio.file.Path.of(command.get(command.indexOf("--output-schema") + 1)));
            assertTrue(java.nio.file.Files.exists(schema.get()));
            return new Output(ExternalAgentResult.Status.SUCCESS, "unstructured", "", 0, 1, false);
          }
        };
    var request =
        new ExternalAgentRequest(
            ExternalAgentRequest.Agent.CODEX,
            ExternalAgentRequest.Action.REVIEW,
            "review",
            root,
            null,
            "design context",
            "run",
            "id");
    assertTrue(
        new CodexExternalAgentExecutor(new CodexProperties(), runner)
            .execute(request, () -> false)
            .success());
    assertFalse(java.nio.file.Files.exists(schema.get()));
  }

  /** 注意事項を保持することを確認する。 */
  @Test
  void parsesStructuredFinalMessageAndPreservesWarnings() {
    String json =
        """
        {"summary":"one issue","findings":[{"severity":"high","title":"bug","reason":"evidence","recommendation":"fix","location":"a:2"}],"warnings":["limited scope"]}
        """;
    String output;
    try {
      output =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "type",
                      "item.completed",
                      "item",
                      Map.of("type", "agent_message", "text", json)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    var result =
        new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
            .parse(
                new ExternalAgentProcessRunner.Output(
                    ExternalAgentResult.Status.SUCCESS, output, "", 0, 10, false));
    assertEquals(ExternalAgentFinding.Severity.high, result.findings().getFirst().severity());
    assertEquals(List.of("limited scope"), result.warnings());
    assertEquals(ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS, result.status());
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void malformedOutputIsNotExecutionFailure() {
    var result =
        new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
            .parse(
                new ExternalAgentProcessRunner.Output(
                    ExternalAgentResult.Status.SUCCESS, "raw review", "", 0, 10, true));
    assertEquals(ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS, result.status());
    assertEquals("raw review", result.rawOutput());
    assertTrue(result.warnings().size() >= 2);
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void cliCommandUsesIsolatedReadOnlyPermissionsAndNoResumeOrShell() {
    var root = java.nio.file.Path.of(".").toAbsolutePath();
    var request =
        new ExternalAgentRequest(
            ExternalAgentRequest.Agent.CODEX,
            ExternalAgentRequest.Action.REVIEW,
            "untrusted task",
            root,
            null,
            "",
            "run",
            "delegation");
    var command =
        new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
            .command(request, root.resolve("schema.json"));
    assertTrue(command.contains("never"));
    assertTrue(command.contains("--ignore-user-config"));
    assertTrue(command.contains("--ignore-rules"));
    assertTrue(command.contains("--strict-config"));
    assertTrue(command.contains("--ephemeral"));
    assertTrue(command.contains("features.hooks=false"));
    assertTrue(command.contains("features.memories=false"));
    assertTrue(command.contains("shell_environment_policy.inherit=\"core\""));
    assertTrue(command.stream().anyMatch(s -> s.contains("network={enabled=false}")));
    assertFalse(
        command.stream()
            .anyMatch(
                s ->
                    s.contains("danger-full-access")
                        || s.contains("workspace-write")
                        || s.equals("resume")));
    assertFalse(command.contains("untrusted task"));
    assertTrue(
        command.stream()
            .anyMatch(s -> s.startsWith("projects={") && s.contains("trust_level=\"untrusted\"")));
    assertFalse(command.stream().anyMatch(s -> s.startsWith("projects.")));
    assertEquals(
        System.getProperty("os.name", "").startsWith("Windows"),
        command.contains("windows.sandbox=\"elevated\""));
  }
}
