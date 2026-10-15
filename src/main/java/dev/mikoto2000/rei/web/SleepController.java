package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.MemoryRunSubmitService;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/memory")
public final class SleepController {
  private final MemoryRunSubmitService runs;
  private final MemoryCommandSupport support;
  private final ProjectRegistry projects;
  private final SessionLifecycle sessions;

  public record Text(String text) {}

  public record SleepRequest(String sessionId, boolean preview) implements StrictApiRequest {}

  public record Cancellation(Long revision) implements StrictApiRequest {}

  public record Maintenance(String sessionId, boolean approve) implements StrictApiRequest {}

  public record Export(String directory) implements StrictApiRequest {}

  private dev.mikoto2000.rei.memory.command.MemoryCommand.ExportCommand exporter;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setExporter(dev.mikoto2000.rei.memory.command.MemoryCommand.ExportCommand exporter) {
    this.exporter = exporter;
  }

  public SleepController(
      MemoryRunSubmitService runs,
      MemoryCommandSupport support,
      ProjectRegistry projects,
      SessionLifecycle sessions) {
    this.runs = runs;
    this.support = support;
    this.projects = projects;
    this.sessions = sessions;
  }

  @PostMapping("/sleep")
  public ResponseEntity<ChatResponse> sleep(
      @PathVariable String projectId,
      @RequestBody SleepRequest request,
      @RequestHeader("Idempotency-Key") String key) {
    if (request == null) throw new IllegalArgumentException("Sleep request required");
    var run = runs.sleep(projectId, request.sessionId(), request.preview(), key);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }

  @PostMapping("/summarize")
  public ResponseEntity<ChatResponse> summarize(
      @PathVariable String projectId,
      @RequestBody Maintenance request,
      @RequestHeader("Idempotency-Key") String key) {
    if (request == null) throw new IllegalArgumentException("Memory request required");
    return accepted(runs.summarize(projectId, request.sessionId(), request.approve(), key));
  }

  @PostMapping("/consolidate")
  public ResponseEntity<ChatResponse> consolidate(
      @PathVariable String projectId,
      @RequestBody Maintenance request,
      @RequestHeader("Idempotency-Key") String key) {
    if (request == null) throw new IllegalArgumentException("Memory request required");
    return accepted(runs.consolidate(projectId, request.sessionId(), request.approve(), key));
  }

  @PostMapping("/export")
  public Text export(@PathVariable String projectId, @RequestBody Export request) {
    project(projectId);
    if (request == null) throw new IllegalArgumentException("Export request required");
    String directory = request.directory();
    if (directory != null
        && (directory.isBlank()
            || directory.length() > 4096
            || directory.chars().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Invalid export directory");
    if (exporter == null) throw new IllegalStateException("Memory export is unavailable");
    return new Text(exporter.render(directory));
  }

  private ResponseEntity<ChatResponse> accepted(dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }

  @GetMapping("/sleep/status")
  public Text status(
      @PathVariable String projectId, @RequestParam(required = false) String sessionId) {
    project(projectId);
    if (sessionId != null) sessions.validate(sessionId, projectId);
    return new Text(support.statusForProject(projectId, sessionId));
  }

  @GetMapping("/sleep/history")
  public Text history(@PathVariable String projectId) {
    project(projectId);
    return new Text(support.historyForProject(projectId));
  }

  @GetMapping("/sleep/requests")
  public Text requests(@PathVariable String projectId) {
    project(projectId);
    return new Text(support.autoSleepRequestsForProject(projectId));
  }

  @PostMapping("/sleep/requests/{sessionId}/cancel")
  public Text cancel(
      @PathVariable String projectId,
      @PathVariable String sessionId,
      @RequestBody Cancellation request) {
    project(projectId);
    sessions.validate(sessionId, projectId);
    if (request == null || request.revision() == null)
      throw new IllegalArgumentException("Observed revision required");
    return new Text(
        support.cancelAutoSleepRequestForProject(projectId, sessionId, request.revision()));
  }

  private void project(String id) {
    projects
        .resolveById(id)
        .orElseThrow(
            () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
  }
}
