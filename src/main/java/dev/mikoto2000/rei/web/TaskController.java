package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.HistoryPage;
import dev.mikoto2000.rei.application.task.TaskControlService;
import dev.mikoto2000.rei.application.task.TaskManagerService;
import dev.mikoto2000.rei.application.task.TaskView;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.task-manager.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/tasks")
public class TaskController {
  private final TaskManagerService tasks;
  private final TaskControlService controls;

  public TaskController(TaskManagerService tasks, TaskControlService controls) {
    this.tasks = tasks;
    this.controls = controls;
  }

  @GetMapping
  public HistoryPage<TaskView> list(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return tasks.list(projectId, sessionId, limit, cursor);
  }

  @GetMapping("/{id}")
  public TaskView get(
      @PathVariable String id,
      @RequestParam String projectId,
      @RequestParam(required = false) String sessionId) {
    return tasks.get(projectId, sessionId, id);
  }

  public record SubmitRequest(
      String projectId, String sessionId, String message, AgentRunContext.Mode mode)
      implements StrictApiRequest {}

  @PostMapping
  public ResponseEntity<TaskView> submit(@RequestBody SubmitRequest request) {
    var task =
        controls.submit(
            request.projectId(), request.sessionId(), request.message(), request.mode());
    var location =
        UriComponentsBuilder.fromPath("/api/v1/tasks/{id}")
            .queryParam("projectId", task.projectId())
            .queryParamIfPresent("sessionId", Optional.ofNullable(task.sessionId()))
            .buildAndExpand(task.id())
            .encode()
            .toUri();
    return ResponseEntity.accepted().location(location).body(task);
  }

  public record ControlRequest(
      String projectId, String sessionId, String expectedRunId, Long expectedRevision)
      implements StrictApiRequest {}

  public record InputRequest(
      String projectId,
      String sessionId,
      String expectedRunId,
      Long expectedRevision,
      String message)
      implements StrictApiRequest {}

  @PostMapping("/{id}/cancel")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView cancel(@PathVariable String id, @RequestBody ControlRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.cancel(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision());
  }

  @PostMapping("/{id}/resume")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView resume(@PathVariable String id, @RequestBody ControlRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.resume(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision());
  }

  @PostMapping("/{id}/suspend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView suspend(@PathVariable String id, @RequestBody ControlRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.suspend(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision());
  }

  @PostMapping("/{id}/input")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView input(@PathVariable String id, @RequestBody InputRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.input(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision(),
        request.message());
  }

  private static void required(String project, Long revision) {
    if (project == null
        || project.isBlank()
        || project.length() > 128
        || revision == null
        || revision < 0)
      throw new IllegalArgumentException("Project and expected Task revision required");
  }
}
