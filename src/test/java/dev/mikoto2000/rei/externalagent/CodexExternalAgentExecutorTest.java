package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodexExternalAgentExecutorTest {
  @Test void authenticationFailureInJsonStdoutIsReportedWithoutLeakingText() {
    var result = new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner()).parse(
        new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.FAILED, "{\"error\":\"401 unauthorized\"}", "", 1, 1, false));
    assertEquals("Codex CLI authentication failed", result.summary());
  }
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
  @Test void unsupportedCliFailsClosedWithoutStartingAReview() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var runner = new ExternalAgentProcessRunner() {
      @Override public Output run(List<String> command, java.nio.file.Path cwd, String input, java.time.Duration total,
          java.time.Duration idle, int limit, java.util.function.BooleanSupplier cancelled) {
        calls.incrementAndGet();
        assertEquals(List.of("codex", "exec", "--help"), command);
        return new Output(ExternalAgentResult.Status.SUCCESS, "old CLI --sandbox", "", 0, 1, false);
      }
    };
    var request = new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX, ExternalAgentRequest.Action.REVIEW,
        "review", root, null, "", "run", "id");
    assertEquals(ExternalAgentResult.Status.UNAVAILABLE, new CodexExternalAgentExecutor(new CodexProperties(), runner).execute(request, () -> false).status());
    assertEquals(1, calls.get());
  }
  @Test void capableCliUsesStdinSchemaAndCanonicalCwdAndRemovesTemporarySchema() {
    var schema = new java.util.concurrent.atomic.AtomicReference<java.nio.file.Path>();
    var runner = new ExternalAgentProcessRunner() {
      @Override public Output run(List<String> command, java.nio.file.Path cwd, String input, java.time.Duration total,
          java.time.Duration idle, int limit, java.util.function.BooleanSupplier cancelled) {
        if (command.contains("--help")) return new Output(ExternalAgentResult.Status.SUCCESS,
            "--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json", "", 0, 1, false);
        assertEquals(root, cwd);
        assertTrue(input.contains("untrusted input"));
        assertTrue(input.contains("Do not run git commit"));
        assertTrue(input.contains("design context"));
        schema.set(java.nio.file.Path.of(command.get(command.indexOf("--output-schema") + 1)));
        assertTrue(java.nio.file.Files.exists(schema.get()));
        return new Output(ExternalAgentResult.Status.SUCCESS, "unstructured", "", 0, 1, false);
      }
    };
    var request = new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX, ExternalAgentRequest.Action.REVIEW,
        "review", root, null, "design context", "run", "id");
    assertTrue(new CodexExternalAgentExecutor(new CodexProperties(), runner).execute(request, () -> false).success());
    assertFalse(java.nio.file.Files.exists(schema.get()));
  }
  @Test void parsesStructuredFinalMessageAndPreservesWarnings() {
    String json = """
        {"summary":"one issue","findings":[{"severity":"high","title":"bug","reason":"evidence","recommendation":"fix","location":"a:2"}],"warnings":["limited scope"]}
        """;
    String output;
    try { output = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("type", "item.completed", "item", Map.of("type", "agent_message", "text", json))); }
    catch (Exception e) { throw new RuntimeException(e); }
    var result = new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
        .parse(new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.SUCCESS, output, "", 0, 10, false));
    assertEquals(ExternalAgentFinding.Severity.high, result.findings().getFirst().severity());
    assertEquals(List.of("limited scope"), result.warnings());
    assertEquals(ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS, result.status());
  }
  @Test void malformedOutputIsNotExecutionFailure() {
    var result = new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
        .parse(new ExternalAgentProcessRunner.Output(ExternalAgentResult.Status.SUCCESS, "raw review", "", 0, 10, true));
    assertEquals(ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS, result.status());
    assertEquals("raw review", result.rawOutput());
    assertTrue(result.warnings().size() >= 2);
  }
  @Test void cliCommandUsesIsolatedReadOnlyPermissionsAndNoResumeOrShell() {
    var root = java.nio.file.Path.of(".").toAbsolutePath();
    var request = new ExternalAgentRequest(ExternalAgentRequest.Agent.CODEX, ExternalAgentRequest.Action.REVIEW,
        "untrusted task", root, null, "", "run", "delegation");
    var command = new CodexExternalAgentExecutor(new CodexProperties(), new ExternalAgentProcessRunner())
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
    assertFalse(command.stream().anyMatch(s -> s.contains("danger-full-access") || s.contains("workspace-write") || s.equals("resume")));
    assertFalse(command.contains("untrusted task"));
  }
}
