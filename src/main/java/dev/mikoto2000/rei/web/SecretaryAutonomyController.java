package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.secretary.SecretaryAutonomy;
import dev.mikoto2000.rei.secretary.SecretaryTask;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit human controls for bounded execution, under the existing authenticated API. */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.secretary.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/secretary")
public class SecretaryAutonomyController {
  private final ObjectProvider<SecretaryAutonomy> services;

  public SecretaryAutonomyController(ObjectProvider<SecretaryAutonomy> services) {
    this.services = services;
  }

  private SecretaryAutonomy service() {
    return services.getObject();
  }

  public record Configure(
      String projectId,
      Boolean enabled,
      String sessionId,
      Integer maxLevel,
      Boolean autoPrepare,
      SecretaryAutonomy.Kind autoKind,
      Integer maxConcurrent,
      Integer maxSeconds,
      Integer maxLlmCalls,
      Integer maxDailyCalls,
      Integer maxRetries)
      implements StrictApiRequest {}

  public record Create(
      String projectId,
      String taskId,
      Long taskRevision,
      String key,
      SecretaryAutonomy.Kind kind,
      SecretaryTask.Status targetStatus,
      String goalId)
      implements StrictApiRequest {}

  public record Control(String projectId, Long revision) implements StrictApiRequest {}

  @GetMapping("/autonomy/settings")
  public SecretaryAutonomy.Settings settings(@RequestParam String projectId) {
    return service().settings(projectId);
  }

  @PutMapping("/autonomy/settings")
  public SecretaryAutonomy.Settings configure(@RequestBody Configure r) {
    return service()
        .configure(
            new SecretaryAutonomy.Settings(
                r.projectId(),
                Boolean.TRUE.equals(r.enabled()),
                r.sessionId(),
                r.maxLevel() == null ? 1 : r.maxLevel(),
                Boolean.TRUE.equals(r.autoPrepare()),
                r.autoKind() == null ? SecretaryAutonomy.Kind.PREPARE : r.autoKind(),
                r.maxConcurrent() == null ? 1 : r.maxConcurrent(),
                r.maxSeconds() == null ? 300 : r.maxSeconds(),
                r.maxLlmCalls() == null ? 8 : r.maxLlmCalls(),
                r.maxDailyCalls() == null ? 32 : r.maxDailyCalls(),
                r.maxRetries() == null ? 1 : r.maxRetries()));
  }

  @GetMapping("/autonomy/status")
  public SecretaryAutonomy.Status status(@RequestParam String projectId) {
    return service().status(projectId);
  }

  @PostMapping("/autonomy/check")
  public void check() {
    service().check();
  }

  @PostMapping("/executions")
  public SecretaryAutonomy.Job create(@RequestBody Create r) {
    if (r.taskRevision() == null) throw new IllegalArgumentException("Task revision required");
    return service()
        .create(
            new SecretaryAutonomy.Request(
                r.projectId(),
                r.taskId(),
                r.taskRevision(),
                r.key(),
                r.kind(),
                r.targetStatus(),
                r.goalId()));
  }

  @GetMapping("/executions")
  public List<SecretaryAutonomy.Job> list(
      @RequestParam String projectId, @RequestParam(defaultValue = "100") int limit) {
    return service().list(projectId, limit);
  }

  @GetMapping("/executions/{id}")
  public SecretaryAutonomy.Job show(@RequestParam String projectId, @PathVariable String id) {
    return service().get(projectId, id);
  }

  @GetMapping("/executions/{id}/history")
  public List<SecretaryAutonomy.History> history(
      @RequestParam String projectId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int limit) {
    return service().history(projectId, id, limit);
  }

  @PostMapping("/executions/{id}/{action}")
  public SecretaryAutonomy.Job control(
      @PathVariable String id, @PathVariable String action, @RequestBody Control r) {
    if (r.revision() == null) throw new IllegalArgumentException("Revision required");
    return switch (action) {
      case "start" -> service().start(r.projectId(), id, r.revision());
      case "cancel" -> service().cancel(r.projectId(), id, r.revision());
      case "suspend" -> service().suspend(r.projectId(), id, r.revision());
      case "resume" -> service().resume(r.projectId(), id, r.revision());
      case "verify" -> service().verify(r.projectId(), id, r.revision());
      default -> throw new IllegalArgumentException("Unknown execution control");
    };
  }
}
