package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.fasterxml.jackson.databind.*;
import static dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status;

/** All CLI/version-specific behavior lives here. Unsupported security capabilities fail closed. */
public class CodexExternalAgentExecutor implements ExternalAgentExecutor {
  private final CodexProperties properties;
  private final ExternalAgentProcessRunner runner;
  private final ObjectMapper mapper = new ObjectMapper();
  public CodexExternalAgentExecutor(CodexProperties properties, ExternalAgentProcessRunner runner) {
    this.properties = properties; this.runner = runner;
  }
  @Override public ExternalAgentResult execute(ExternalAgentRequest request, BooleanSupplier cancelled) {
    if (!properties.isEnabled()) return ExternalAgentResult.rejected("Codex external reviews are disabled");
    if (properties.getTotalTimeout() == null || properties.getTotalTimeout().isNegative() || properties.getTotalTimeout().isZero()
        || properties.getInactivityTimeout() == null || properties.getInactivityTimeout().isNegative() || properties.getInactivityTimeout().isZero()
        || properties.getMaxOutputBytes() < 1) return ExternalAgentResult.rejected("Invalid Codex execution limits");
    long start = System.nanoTime();
    var capability = runner.run(List.of(properties.getCommand(), "exec", "--help"), request.projectRoot(), "",
        min(properties.getTotalTimeout(), Duration.ofSeconds(10)), min(properties.getInactivityTimeout(), Duration.ofSeconds(10)),
        65536, cancelled);
    if (capability.status() != Status.SUCCESS) return parse(capability);
    if (!List.of("--ignore-user-config", "--ignore-rules", "--strict-config", "--ephemeral", "--output-schema", "--json")
        .stream().allMatch(capability.stdout()::contains))
      return new ExternalAgentResult(Status.UNAVAILABLE,
          "Codex CLI lacks required isolated configuration/permission capabilities; update Codex CLI", List.of(), List.of(), capability.duration(), null, "");
    Path schema = null;
    try {
      schema = Files.createTempFile("rei-codex-review-", ".json");
      Files.writeString(schema, resource("schema.json"));
      String prompt = resource("review.txt") + "\nReview request data (JSON):\n" + mapper.writeValueAsString(Map.of(
          "task", request.task(), "target", request.target() == null ? "current repository" : request.projectRoot().relativize(request.target()).toString(),
          "context", request.context()));
      Duration remaining = properties.getTotalTimeout().minusNanos(System.nanoTime() - start);
      if (remaining.isNegative() || remaining.isZero()) return new ExternalAgentResult(Status.TOTAL_TIMEOUT,
          "Codex review total timeout", List.of(), List.of(), capability.duration(), null, "");
      return parse(runner.run(command(request, schema), request.projectRoot(), prompt, remaining,
          properties.getInactivityTimeout(), properties.getMaxOutputBytes(), cancelled));
    } catch (java.io.IOException error) {
      return new ExternalAgentResult(Status.UNAVAILABLE, "Could not prepare Codex review", List.of(), List.of(), 0, null, "");
    } finally {
      if (schema != null) try { Files.deleteIfExists(schema); } catch (java.io.IOException ignored) { }
    }
  }
  List<String> command(ExternalAgentRequest request, Path schema) {
    String project = request.projectRoot().toString().replace('\\', '/').replace("\"", "\\\"");
    return List.of(properties.getCommand(), "--ask-for-approval", "never", "exec", "--ignore-user-config", "--ignore-rules",
        "--strict-config", "--ephemeral", "--json", "--color", "never", "--skip-git-repo-check",
        "--cd", request.projectRoot().toString(), "--output-schema", schema.toString(),
        "-c", "projects.\"" + project + "\".trust_level=\"untrusted\"",
        "-c", "default_permissions=\"rei_review\"",
        "-c", "permissions.rei_review={filesystem={\":minimal\"=\"read\",\":workspace_roots\"={\".\"=\"read\",\"**/.env\"=\"deny\",\"**/.env.*\"=\"deny\",\"**/*.pem\"=\"deny\",\"**/*.key\"=\"deny\",\"**/credentials.*\"=\"deny\",\"**/secrets.*\"=\"deny\"}},network={enabled=false}}",
        "-c", "web_search=\"disabled\"", "-c", "features.apps=false", "-c", "features.multi_agent=false",
        "-c", "features.hooks=false", "-c", "features.memories=false", "-c", "features.goals=false",
        "-c", "shell_environment_policy.inherit=\"core\"", "-c", "history.persistence=\"none\"",
        "-c", "mcp_servers={}", "-c", "plugins={}", "-c", "notify=[]", "-c", "project_doc_max_bytes=0", "-");
  }
  ExternalAgentResult parse(ExternalAgentProcessRunner.Output output) {
    List<String> warnings = new ArrayList<>();
    if (output.truncated()) warnings.add("Process output was truncated at the configured byte limit");
    if (output.status() != Status.SUCCESS) {
      String reason = switch (output.status()) {
        case UNAVAILABLE -> "Codex CLI is not available or could not be started";
        case TOTAL_TIMEOUT -> "Codex review total timeout";
        case INACTIVITY_TIMEOUT -> "Codex review inactivity timeout";
        case CANCELLED -> "Codex review cancelled";
        default -> (output.stderr() + output.stdout()).toLowerCase(Locale.ROOT).matches("(?s).*(unauthorized|authentication|not logged in|401).*" )
            ? "Codex CLI authentication failed" : "Codex CLI exited unsuccessfully";
      };
      return new ExternalAgentResult(output.status(), reason, List.of(), warnings, output.duration(), output.exitCode(), output.stdout());
    }
    String finalText = "";
    try {
      for (String line : output.stdout().split("\\R")) {
        try {
          JsonNode event = mapper.readTree(line);
          if (event.path("type").asText().equals("item.completed") && event.path("item").path("type").asText().equals("agent_message"))
            finalText = event.path("item").path("text").asText();
        } catch (Exception ignored) { }
      }
      JsonNode review = mapper.readTree(finalText);
      if (review == null || !review.path("summary").isTextual() || !review.path("findings").isArray() || !review.path("warnings").isArray())
        throw new IllegalArgumentException("Invalid structured review");
      List<ExternalAgentFinding> findings = new ArrayList<>();
      for (JsonNode finding : review.path("findings")) {
        if (findings.size() >= 32) { warnings.add("Findings limited to 32"); break; }
        findings.add(new ExternalAgentFinding(ExternalAgentFinding.Severity.valueOf(finding.path("severity").asText()),
            text(finding, "title", 300), text(finding, "reason", 1500), text(finding, "recommendation", 1500),
            finding.path("location").isNull() ? null : text(finding, "location", 500)));
      }
      for (JsonNode warning : review.path("warnings")) {
        if (warnings.size() >= 32) break;
        warnings.add(ExternalAgentDelegationService.bounded(warning.asText(), 500));
      }
      return new ExternalAgentResult(warnings.isEmpty() ? Status.SUCCESS : Status.SUCCESS_WITH_WARNINGS,
          text(review, "summary", 4000), findings, warnings, output.duration(), output.exitCode(), output.stdout());
    } catch (Exception error) {
      warnings.add("Could not parse structured findings; review text requires independent evaluation");
      return new ExternalAgentResult(Status.SUCCESS_WITH_WARNINGS,
          finalText.isBlank() ? "No structured final review was available" : ExternalAgentDelegationService.bounded(finalText, 8000),
          List.of(), warnings, output.duration(), output.exitCode(), output.stdout());
    }
  }
  private String text(JsonNode node, String key, int limit) {
    if (!node.path(key).isTextual()) throw new IllegalArgumentException("Missing review field");
    return ExternalAgentDelegationService.bounded(node.path(key).asText(), limit);
  }
  private static Duration min(Duration a, Duration b) { return a.compareTo(b) <= 0 ? a : b; }
  private String resource(String name) throws java.io.IOException {
    try (var stream = getClass().getResourceAsStream("/external-agent/" + name)) {
      if (stream == null) throw new java.io.IOException("Missing review resource");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
