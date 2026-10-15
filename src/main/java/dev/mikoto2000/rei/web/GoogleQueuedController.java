package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** CLI operations can wait for implicit OAuth without tying up an HTTP request. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/google-operations")
public final class GoogleQueuedController {
  private final IntegrationRunSubmitService runs;

  public GoogleQueuedController(IntegrationRunSubmitService runs) {
    this.runs = runs;
  }

  public record CalendarList(
      String projectId, String sessionId, LocalDate date, String from, String to)
      implements StrictApiRequest {}

  public record CalendarAdd(
      String projectId,
      String sessionId,
      String title,
      String start,
      String end,
      String location,
      String description)
      implements StrictApiRequest {}

  public record TaskList(String projectId, String sessionId, Integer priority, LocalDate dueBefore)
      implements StrictApiRequest {}

  public record TaskAdd(
      String projectId, String sessionId, String title, LocalDate due, Integer priority)
      implements StrictApiRequest {}

  public record Scope(String projectId, String sessionId) implements StrictApiRequest {}

  @PostMapping("/calendar/list")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> calendarList(
      @RequestBody CalendarList request) {
    scope(request.projectId(), request.sessionId());
    optional(request.from());
    optional(request.to());
    return accepted(
        runs.listCalendar(
            request.projectId(),
            request.sessionId(),
            request.date(),
            request.from(),
            request.to()));
  }

  @PostMapping("/calendar/add")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> calendarAdd(
      @RequestBody CalendarAdd request) {
    scope(request.projectId(), request.sessionId());
    required(request.title());
    required(request.start());
    required(request.end());
    optional(request.location());
    optional(request.description());
    return accepted(
        runs.addCalendarEvent(
            request.projectId(),
            request.sessionId(),
            request.title(),
            request.start(),
            request.end(),
            request.location(),
            request.description()));
  }

  @PostMapping("/tasks/list")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> taskList(
      @RequestBody TaskList request) {
    scope(request.projectId(), request.sessionId());
    return accepted(
        runs.listGoogleTasks(
            request.projectId(), request.sessionId(), request.priority(), request.dueBefore()));
  }

  @PostMapping("/tasks/add")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> taskAdd(
      @RequestBody TaskAdd request) {
    scope(request.projectId(), request.sessionId());
    required(request.title());
    return accepted(
        runs.addGoogleTask(
            request.projectId(),
            request.sessionId(),
            request.title(),
            request.due(),
            request.priority() == null ? 3 : request.priority()));
  }

  @PostMapping("/tasks/{id}/complete")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> complete(
      @PathVariable long id, @RequestBody Scope request) {
    scope(request.projectId(), request.sessionId());
    return accepted(runs.completeGoogleTask(request.projectId(), request.sessionId(), id));
  }

  @PostMapping("/tasks/{id}/delete")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> delete(
      @PathVariable long id, @RequestBody Scope request) {
    scope(request.projectId(), request.sessionId());
    return accepted(runs.deleteGoogleTask(request.projectId(), request.sessionId(), id));
  }

  private static void scope(String project, String session) {
    required(project);
    optional(session);
  }

  private static void required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Value required");
    optional(value);
  }

  private static void optional(String value) {
    if (value != null && (value.length() > 16384 || value.indexOf('\0') >= 0))
      throw new IllegalArgumentException("Invalid value");
  }

  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
