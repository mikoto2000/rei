package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.secretary.SecretaryOrchestrator;
import java.time.Instant;
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

/** Human-facing suggestion controls, protected by the existing API authentication. */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.secretary.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/secretary")
public class SecretaryOrchestratorController {
  private final ObjectProvider<SecretaryOrchestrator> services;

  public SecretaryOrchestratorController(ObjectProvider<SecretaryOrchestrator> services) {
    this.services = services;
  }

  private SecretaryOrchestrator service() {
    return services.getObject();
  }

  public record Configure(
      String projectId,
      Boolean enabled,
      Boolean stopped,
      Instant snoozedUntil,
      Long minimumSeconds,
      Long repeatSeconds)
      implements StrictApiRequest {}

  public record Project(String projectId) implements StrictApiRequest {}

  public record Feedback(
      String projectId, Long revision, SecretaryOrchestrator.Feedback reaction, Long delaySeconds)
      implements StrictApiRequest {}

  @GetMapping("/orchestrator/settings")
  public SecretaryOrchestrator.Settings settings(@RequestParam String projectId) {
    return service().settings(projectId);
  }

  @PutMapping("/orchestrator/settings")
  public SecretaryOrchestrator.Settings configure(@RequestBody Configure request) {
    return service()
        .configure(
            new SecretaryOrchestrator.Settings(
                request.projectId(),
                Boolean.TRUE.equals(request.enabled()),
                Boolean.TRUE.equals(request.stopped()),
                request.snoozedUntil(),
                request.minimumSeconds() == null ? 900 : request.minimumSeconds(),
                request.repeatSeconds() == null ? 86400 : request.repeatSeconds()));
  }

  @GetMapping("/orchestrator/status")
  public SecretaryOrchestrator.Status status(@RequestParam String projectId) {
    return service().status(projectId);
  }

  @GetMapping("/preparation")
  public List<SecretaryOrchestrator.Preparation> preparation(@RequestParam String projectId) {
    return service().preparation(projectId);
  }

  @PostMapping("/orchestrator/evaluate")
  public List<SecretaryOrchestrator.Suggestion> evaluate(@RequestBody Project request) {
    return service().evaluate(request.projectId());
  }

  @PostMapping("/orchestrator/release")
  public int release(@RequestBody Project request) {
    return service().release(request.projectId());
  }

  @GetMapping("/suggestions")
  public List<SecretaryOrchestrator.Suggestion> list(
      @RequestParam String projectId, @RequestParam(defaultValue = "100") int limit) {
    return service().suggestions(projectId, limit);
  }

  @GetMapping("/suggestions/{id}")
  public SecretaryOrchestrator.Suggestion show(
      @RequestParam String projectId, @PathVariable String id) {
    return service().suggestion(projectId, id);
  }

  @GetMapping("/suggestions/{id}/history")
  public List<SecretaryOrchestrator.History> history(
      @RequestParam String projectId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int limit) {
    return service().history(projectId, id, limit);
  }

  @PostMapping("/suggestions/{id}/feedback")
  public SecretaryOrchestrator.Suggestion feedback(
      @PathVariable String id, @RequestBody Feedback request) {
    if (request.revision() == null) throw new IllegalArgumentException("Revision required");
    return service()
        .feedback(
            request.projectId(),
            id,
            request.revision(),
            request.reaction(),
            request.delaySeconds() == null ? 0 : request.delaySeconds());
  }
}
