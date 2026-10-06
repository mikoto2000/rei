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
  @Override public boolean supportsContinuation() { return properties.isEnabled() && properties.isPersistSessions(); }
  @Override public ExternalAgentResult execute(ExternalAgentRequest request, BooleanSupplier cancelled) {
    if (!properties.isEnabled()) return ExternalAgentResult.rejected("Codex external reviews are disabled");
    if(request.externalSessionId()!=null && (!supportsContinuation() || !ExternalAgentResult.validSessionId(request.externalSessionId())))
      return ExternalAgentResult.rejected("An enabled, saved native session UUID is required");
    if (properties.getTotalTimeout() == null || properties.getTotalTimeout().isNegative() || properties.getTotalTimeout().isZero()
        || properties.getInactivityTimeout() == null || properties.getInactivityTimeout().isNegative() || properties.getInactivityTimeout().isZero()
        || properties.getMaxOutputBytes() < 1) return ExternalAgentResult.rejected("Invalid Codex execution limits");
    long start = System.nanoTime();
    var capability = runner.run(List.of(executable(), "exec", "--help"), request.projectRoot(), "",
        min(properties.getTotalTimeout(), Duration.ofSeconds(10)), min(properties.getInactivityTimeout(), Duration.ofSeconds(10)),
        65536, cancelled);
    if (capability.status() != Status.SUCCESS) return parse(capability);
    if (!List.of("--ignore-user-config", "--ignore-rules", "--strict-config", "--ephemeral", "--output-schema", "--json")
        .stream().allMatch(capability.stdout()::contains))
      return new ExternalAgentResult(Status.UNAVAILABLE,
          "Codex CLI lacks required isolated configuration/permission capabilities; update Codex CLI", List.of(), List.of(), capability.duration(), null, "");
    if(request.externalSessionId()!=null) {
      Duration remaining=properties.getTotalTimeout().minusNanos(System.nanoTime()-start);
      if(remaining.isNegative() || remaining.isZero())return new ExternalAgentResult(Status.TOTAL_TIMEOUT,"Codex review total timeout",List.of(),List.of(),capability.duration(),null,"");
      var resume=runner.run(List.of(executable(),"exec","resume","--help"),request.projectRoot(),"",
          min(remaining,Duration.ofSeconds(10)),min(properties.getInactivityTimeout(),Duration.ofSeconds(10)),65536,cancelled);
      if(resume.status()!=Status.SUCCESS)return parse(resume);
      if(!List.of("--ignore-user-config","--ignore-rules","--strict-config","--output-schema","--json").stream().allMatch(resume.stdout()::contains))
        return new ExternalAgentResult(Status.UNAVAILABLE,"Codex CLI lacks isolated resume capabilities",List.of(),List.of(),resume.duration(),null,"");
    }
    Path schema = null;
    try {
      schema = Files.createTempFile("rei-codex-review-", ".json");
      boolean fix=request.action()==ExternalAgentRequest.Action.PROPOSE_FIX;
      Files.writeString(schema, resource(fix?"fix-proposal-schema.json":"schema.json"));
      String prompt = resource(fix?"fix-proposal.txt":"review.txt") + "\nReview request data (JSON):\n" + mapper.writeValueAsString(Map.of(
          "task", request.task(), "target", request.target() == null ? "current repository" : request.projectRoot().relativize(request.target()).toString(),
          "context", request.context()));
      Duration remaining = properties.getTotalTimeout().minusNanos(System.nanoTime() - start);
      if (remaining.isNegative() || remaining.isZero()) return new ExternalAgentResult(Status.TOTAL_TIMEOUT,
          "Codex review total timeout", List.of(), List.of(), capability.duration(), null, "");
      var result=parse(runner.run(command(request, schema), request.projectRoot(), prompt, remaining,
          properties.getInactivityTimeout(), properties.getMaxOutputBytes(), cancelled),fix);
      if(request.externalSessionId()!=null && result.success() && !request.externalSessionId().equals(result.externalSessionId()))
        return new ExternalAgentResult(Status.FAILED,"Continued review did not confirm the saved native session; request a fresh re-review",List.of(),List.of(),result.duration(),result.exitCode(),"");
      return result;
    } catch (java.io.IOException error) {
      return new ExternalAgentResult(Status.UNAVAILABLE, "Could not prepare Codex review", List.of(), List.of(), 0, null, "");
    } finally {
      if (schema != null) try { Files.deleteIfExists(schema); } catch (java.io.IOException ignored) { }
    }
  }
  private String executable() {
    List<Path> directories = new ArrayList<>();
    for (String entry : Objects.toString(System.getenv("PATH"), "").split(java.io.File.pathSeparator)) {
      try {
        Path path = Path.of(entry.replace("\"", ""));
        if (path.isAbsolute()) directories.add(path);
      } catch (InvalidPathException ignored) { }
    }
    return resolveCommand(properties.getCommand(), System.getProperty("os.name", ""), directories);
  }
  /** Windows CreateProcess cannot execute npm shell shims. Resolve only native binaries. */
  static String resolveCommand(String configured, String osName, List<Path> directories) {
    if (!"codex".equals(configured) || !osName.startsWith("Windows")) return configured;
    for (Path directory : directories) {
      Path binary = directory.resolve("codex.exe");
      if (Files.isRegularFile(binary)) return binary.toString();
    }
    String arch = System.getProperty("os.arch", "").equals("aarch64") ? "arm64" : "x64";
    String triple = arch.equals("arm64") ? "aarch64-pc-windows-msvc" : "x86_64-pc-windows-msvc";
    for (Path directory : directories) {
      for (String packagePath : List.of("node_modules/@openai/codex/node_modules/@openai/codex-win32-" + arch,
          "node_modules/@openai/codex-win32-" + arch, "node_modules/@openai/codex")) {
        for (String bin : List.of("bin", "codex")) {
          Path binary = directory.resolve(packagePath + "/vendor/" + triple + "/" + bin + "/codex.exe");
          if (Files.isRegularFile(binary)) return binary.toString();
        }
      }
    }
    return "codex.exe";
  }
  List<String> command(ExternalAgentRequest request, Path schema) {
    String project = request.projectRoot().toString().replace('\\', '/').replace("\"", "\\\"");
    List<String> command = new ArrayList<>(List.of(executable(), "--ask-for-approval", "never", "exec", "--ignore-user-config", "--ignore-rules",
        "--strict-config", "--ephemeral", "--json", "--color", "never", "--skip-git-repo-check",
        "--cd", request.projectRoot().toString(), "--output-schema", schema.toString(),
        "-c", "projects={\"" + project + "\"={trust_level=\"untrusted\"}}",
        "-c", "default_permissions=\"rei_review\"",
        "-c", "permissions.rei_review={filesystem={\":minimal\"=\"read\",\":workspace_roots\"={\".\"=\"read\",\"**/.env\"=\"deny\",\"**/.env.*\"=\"deny\",\"**/*.pem\"=\"deny\",\"**/*.key\"=\"deny\",\"**/credentials.*\"=\"deny\",\"**/secrets.*\"=\"deny\"}},network={enabled=false}}",
        "-c", "web_search=\"disabled\"", "-c", "features.apps=false", "-c", "features.multi_agent=false",
        "-c", "features.hooks=false", "-c", "features.memories=false", "-c", "features.goals=false",
        "-c", "shell_environment_policy.inherit=\"core\"", "-c", "history.persistence=\"none\"",
        "-c", "mcp_servers={}", "-c", "plugins={}", "-c", "notify=[]", "-c", "project_doc_max_bytes=0", "-"));
    if (System.getProperty("os.name", "").startsWith("Windows")) {
      command.add(command.size() - 1, "-c");
      command.add(command.size() - 1, "windows.sandbox=\"elevated\"");
    }
    if(properties.isPersistSessions())command.remove("--ephemeral");
    if(request.externalSessionId()!=null) {
      int color=command.indexOf("--color");command.remove(color+1);command.remove(color);
      int directory=command.indexOf("--cd");command.remove(directory);command.remove(directory);
      command.addAll(command.indexOf("exec"),List.of("--cd",request.projectRoot().toString()));
      command.add(command.indexOf("exec")+1,"resume");
      command.add(command.size()-1,request.externalSessionId());
    }
    return List.copyOf(command);
  }
  private String failureDiagnostic(ExternalAgentProcessRunner.Output output) {
    String diagnostic = output.stderr();
    for (String line : output.stdout().split("\\R")) {
      try {
        JsonNode event = mapper.readTree(line);
        String message = switch (event.path("type").asText()) {
          case "error" -> event.path("message").asText();
          case "turn.failed" -> event.path("error").path("message").asText();
          default -> "";
        };
        if (!message.isBlank()) diagnostic = message;
      } catch (Exception ignored) { }
    }
    return ExternalAgentDelegationService.bounded(dev.mikoto2000.rei.event.CredentialRedactor.redact(diagnostic), 800);
  }
  ExternalAgentResult parse(ExternalAgentProcessRunner.Output output) {
    return parse(output,false);
  }
  private ExternalAgentResult parse(ExternalAgentProcessRunner.Output output,boolean fixProposal) {
    List<String> warnings = new ArrayList<>();
    if (output.truncated()) warnings.add("Process output was truncated at the configured byte limit");
    if (output.status() != Status.SUCCESS) {
      String reason = switch (output.status()) {
        case UNAVAILABLE -> "Codex CLI could not be started. Check rei.external-agents.codex.command and the PATH inherited by rei; "
            + "on Windows, configure the absolute path to native codex.exe (not codex.cmd or codex.ps1). OS error: "
            + ExternalAgentDelegationService.bounded(dev.mikoto2000.rei.event.CredentialRedactor.redact(output.stderr()), 800);
        case TOTAL_TIMEOUT -> "Codex review total timeout";
        case INACTIVITY_TIMEOUT -> "Codex review inactivity timeout";
        case CANCELLED -> "Codex review cancelled";
        default -> (output.stderr() + output.stdout()).toLowerCase(Locale.ROOT).matches("(?s).*(unauthorized|authentication|not logged in|401).*" )
            ? "Codex CLI authentication failed" : "Codex CLI exited unsuccessfully. Diagnostic: " + failureDiagnostic(output);
      };
      return new ExternalAgentResult(output.status(), reason, List.of(), warnings, output.duration(), output.exitCode(), output.stdout());
    }
    String finalText = "";
    String sessionId=null;
    boolean invalidSession=false;
    try {
      if(fixProposal && output.truncated())throw new IllegalArgumentException("Incomplete fix proposal output");
      for (String line : output.stdout().split("\\R")) {
        try {
          JsonNode event = mapper.readTree(line);
          if(event.path("type").asText().equals("thread.started")) {
            String candidate=event.path("thread_id").asText();
            if(!ExternalAgentResult.validSessionId(candidate) || (sessionId!=null && !sessionId.equals(candidate)))invalidSession=true;
            else sessionId=candidate;
          }
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
      dev.mikoto2000.rei.core.TextChangeSetService.Request proposal=null;
      if(fixProposal) {
        var draft=review.get("proposal");
        if(draft==null)throw new IllegalArgumentException("Missing fix proposal field");
        if(!draft.isNull()) {
          if(!draft.isObject() || draft.size()!=3)throw new IllegalArgumentException("Invalid fix proposal");
          String path=proposalText(draft,"path",1024),before=proposalText(draft,"expectedText",65536),after=proposalText(draft,"replacement",65536);
          if(path.isBlank() || before.isEmpty())throw new IllegalArgumentException("Nonempty path and baseline required");
          proposal=new dev.mikoto2000.rei.core.TextChangeSetService.Request(path,before,after);
        }
      }
      return new ExternalAgentResult(warnings.isEmpty() ? Status.SUCCESS : Status.SUCCESS_WITH_WARNINGS,
          text(review, "summary", 4000), findings, warnings, output.duration(), output.exitCode(), output.stdout(),null,
          properties.isPersistSessions() && !invalidSession && !output.truncated()?sessionId:null,proposal,null);
    } catch (Exception error) {
      if(fixProposal)return new ExternalAgentResult(Status.FAILED,"Could not parse a complete structured fix proposal; no Change Set created",List.of(),List.of(),output.duration(),output.exitCode(),"");
      warnings.add("Could not parse structured findings; review text requires independent evaluation");
      return new ExternalAgentResult(Status.SUCCESS_WITH_WARNINGS,
          finalText.isBlank() ? "No structured final review was available" : ExternalAgentDelegationService.bounded(finalText, 8000),
          List.of(), warnings, output.duration(), output.exitCode(), output.stdout());
    }
  }
  private String proposalText(JsonNode node,String key,int limit) {
    if(!node.path(key).isTextual())throw new IllegalArgumentException("Missing proposal text");
    String value=node.path(key).asText();
    if(value.getBytes(StandardCharsets.UTF_8).length>limit)throw new IllegalArgumentException("Proposal text exceeds limit");
    return value; // Never redact or truncate the exact baseline or replacement.
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
