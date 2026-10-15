package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.llm.capture.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/llm/capture")
public final class CaptureCommandsController {
  private final CaptureStore store;
  private final ProjectRegistry projects;
  private final SessionLifecycle sessions;

  public CaptureCommandsController(
      CaptureStore store, ProjectRegistry projects, SessionLifecycle sessions) {
    this.store = store;
    this.projects = projects;
    this.sessions = sessions;
  }

  public record Text(String text) {}

  public record Scope(String projectId, String sessionId) implements StrictApiRequest {}

  public record Approval(Boolean approved) implements StrictApiRequest {}

  public record Export(String path, Boolean approved) implements StrictApiRequest {}

  @PostMapping("/reservation")
  public Text next(@RequestHeader("X-Rei-Client-Id") String client, @RequestBody Scope scope) {
    var command = scoped(client, scope.projectId(), scope.sessionId());
    return new Text(command.reportNext());
  }

  @GetMapping("/status")
  public Text status(
      @RequestHeader("X-Rei-Client-Id") String client,
      @RequestParam String projectId,
      @RequestParam String sessionId) {
    return new Text(scoped(client, projectId, sessionId).reportStatus());
  }

  @PostMapping("/reservation/off")
  public Text off(@RequestHeader("X-Rei-Client-Id") String client, @RequestBody Scope scope) {
    return new Text(scoped(client, scope.projectId(), scope.sessionId()).reportOff());
  }

  @GetMapping("/sessions")
  public Text list() {
    return new Text(command().reportSessions());
  }

  @GetMapping("/sessions/{run}/attempts")
  public Text attempts(@PathVariable String run) {
    id(run);
    return new Text(command().reportAttempts(run));
  }

  @GetMapping("/attempts/{attempt}")
  public CaptureStore.AttemptInfo attempt(@PathVariable String attempt) {
    id(attempt);
    return store.attempt(attempt);
  }

  @GetMapping("/attempts/{attempt}/show")
  public Text show(@PathVariable String attempt) {
    id(attempt);
    return new Text(command().reportShow(attempt));
  }

  @PostMapping("/attempts/{attempt}/raw")
  public Text raw(@PathVariable String attempt, @RequestBody Approval request) {
    approved(request == null ? null : request.approved());
    id(attempt);
    var command = command();
    command.setConfirmation(prompt -> true);
    return new Text(command.reportRaw(attempt));
  }

  @PostMapping("/attempts/{attempt}/export")
  public Text export(@PathVariable String attempt, @RequestBody Export request) {
    approved(request == null ? null : request.approved());
    id(attempt);
    if (request.path() == null
        || request.path().isBlank()
        || request.path().length() > 16384
        || request.path().indexOf('\0') >= 0)
      throw new IllegalArgumentException("Export path required");
    var command = command();
    command.setConfirmation(prompt -> true);
    return new Text(command.reportExport(attempt, request.path()));
  }

  @DeleteMapping("/sessions/{run}")
  public Text delete(@PathVariable String run) {
    id(run);
    return new Text(command().reportDelete(run));
  }

  @DeleteMapping("/sessions")
  public Text clear() {
    return new Text(command().reportClear());
  }

  private LlmCaptureCommand scoped(String client, String project, String session) {
    if (client == null
        || !client.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("Invalid CLI identity");
    projects
        .resolveById(project)
        .orElseThrow(
            () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    sessions.validate(session, project);
    return new LlmCaptureCommand(store, () -> "cli:" + client, () -> session, () -> {});
  }

  private LlmCaptureCommand command() {
    return new LlmCaptureCommand(store, () -> null, () -> null, () -> {});
  }

  private static void approved(Boolean value) {
    if (!Boolean.TRUE.equals(value))
      throw new IllegalArgumentException("Explicit original-body confirmation required");
  }

  private static void id(String value) {
    if (value == null || !value.matches("[A-Za-z0-9:_-]{1,512}"))
      throw new IllegalArgumentException("Invalid capture identifier");
  }
}
