package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.command.StorageCommand;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixed maintenance endpoints preserve the existing retention consent and protected-object checks.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/storage")
public final class StorageController {
  private final StorageCommand storage;
  private final ProjectRegistry projects;

  public record Text(String text) {}

  public record Plan(
      String projectId, boolean current, String category, Integer maxObjects, Long maxBytes)
      implements StrictApiRequest {}

  public record Policy(
      String category,
      String retentionDays,
      Long maxCount,
      Long maxPolicyBytes,
      boolean clearCount,
      boolean clearPolicyBytes)
      implements StrictApiRequest {}

  public record Automatic(
      String projectId,
      boolean current,
      String category,
      Integer maxObjects,
      Long maxBytes,
      Long intervalSeconds)
      implements StrictApiRequest {}

  public record Pages(Integer pages) implements StrictApiRequest {}

  public record Window(Long windowSeconds) implements StrictApiRequest {}

  public StorageController(StorageCommand storage, ProjectRegistry projects) {
    this.storage = storage;
    this.projects = projects;
  }

  @GetMapping("/status")
  public Text status(
      @RequestParam(required = false) String projectId,
      @RequestParam(defaultValue = "false") boolean current) {
    return scoped(projectId, current, () -> storage.reportStatus(current ? "current" : null));
  }

  @PostMapping("/retention/plans")
  public Text plan(@RequestBody Plan request) {
    if (request == null) throw new IllegalArgumentException("Plan required");
    optional(request.category());
    return scoped(
        request.projectId(),
        request.current(),
        () ->
            storage.reportPlan(
                request.category() == null ? "raw-results" : request.category(),
                request.current() ? "current" : null,
                request.maxObjects() == null ? 100 : request.maxObjects(),
                request.maxBytes() == null ? 16777216 : request.maxBytes()));
  }

  @PostMapping("/retention/plans/{id}/approve")
  public Text approve(@PathVariable String id) {
    required(id);
    return text(storage.reportApprove(id));
  }

  @PostMapping("/retention/plans/{id}/apply")
  public Text apply(@PathVariable String id) {
    required(id);
    return text(storage.reportApply(id));
  }

  @GetMapping("/retention/history")
  public Text history() {
    return text(storage.reportRetentionHistory());
  }

  @PostMapping("/retention/executions/{id}/restore")
  public Text restore(@PathVariable String id) {
    required(id);
    return text(storage.reportRestore(id));
  }

  @PostMapping("/retention/executions/{id}/approve-purge")
  public Text approvePurge(@PathVariable String id) {
    required(id);
    return text(storage.reportApprovePurge(id));
  }

  @PostMapping("/retention/executions/{id}/purge")
  public Text purge(@PathVariable String id) {
    required(id);
    return text(storage.reportPurge(id));
  }

  @PostMapping("/retention/policy")
  public Text policy(@RequestBody Policy request) {
    if (request == null) throw new IllegalArgumentException("Policy required");
    required(request.category());
    optional(request.retentionDays());
    return text(
        storage.reportPolicy(
            request.category(),
            request.retentionDays(),
            request.maxCount(),
            request.maxPolicyBytes(),
            request.clearCount(),
            request.clearPolicyBytes()));
  }

  @PostMapping("/retention/automatic/proposals")
  public Text propose(@RequestBody Automatic request) {
    if (request == null
        || request.maxObjects() == null
        || request.maxBytes() == null
        || request.intervalSeconds() == null)
      throw new IllegalArgumentException("Explicit automatic retention limits required");
    required(request.category());
    return scoped(
        request.projectId(),
        request.current(),
        () ->
            storage.reportAutoPropose(
                request.category(),
                request.current() ? "current" : null,
                request.maxObjects(),
                request.maxBytes(),
                request.intervalSeconds()));
  }

  @PostMapping("/retention/automatic/{id}/approve")
  public Text approveAutomatic(@PathVariable String id) {
    required(id);
    return text(storage.reportAutoApprove(id));
  }

  @PostMapping("/retention/automatic/{id}/disable")
  public Text disableAutomatic(@PathVariable String id) {
    required(id);
    return text(storage.reportAutoDisable(id));
  }

  @GetMapping("/retention/automatic/history")
  public Text automaticHistory() {
    return text(storage.reportAutoHistory());
  }

  @GetMapping("/maintenance/status")
  public Text maintenanceStatus() {
    return text(storage.reportMaintenanceStatus());
  }

  @PostMapping("/maintenance/checkpoint")
  public Text checkpoint() {
    return text(storage.reportCheckpoint());
  }

  @PostMapping("/maintenance/incremental-vacuum")
  public Text incrementalVacuum(@RequestBody Pages request) {
    if (request == null || request.pages() == null)
      throw new IllegalArgumentException("Explicit page budget required");
    return text(storage.reportIncrementalVacuum(request.pages()));
  }

  @PostMapping("/maintenance/vacuum")
  public Text vacuum(@RequestBody Window request) {
    if (request == null || request.windowSeconds() == null)
      throw new IllegalArgumentException("Explicit maintenance window required");
    return text(storage.reportVacuum(request.windowSeconds()));
  }

  private Text scoped(String projectId, boolean current, Supplier<String> operation) {
    if (projectId == null) {
      if (current) throw new IllegalArgumentException("Select a Project first");
      return text(operation.get());
    }
    required(projectId);
    var project =
        projects
            .resolveById(projectId)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext(
                UUID.randomUUID().toString(),
                "",
                project.root(),
                project.id(),
                AgentRunContext.RequestSource.WEB))) {
      return text(operation.get());
    }
  }

  private static void required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Value required");
    optional(value);
  }

  private static void optional(String value) {
    if (value != null && (value.length() > 512 || value.chars().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Invalid value");
  }

  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }
}
