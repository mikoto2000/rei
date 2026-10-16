package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Human-facing API only; deciding does not dispatch or execute the Tool. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/approvals")
public class ApprovalController {
  private final ToolApprovalRepository repository;
  private dev.mikoto2000.rei.core.policy.ApprovalContinuationService continuations;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setContinuations(
      dev.mikoto2000.rei.core.policy.ApprovalContinuationService continuations) {
    this.continuations = continuations;
  }

  public ApprovalController(ToolApprovalRepository repository) {
    this.repository = repository;
  }

  public record View(
      String id,
      String projectId,
      String sessionId,
      String runId,
      String tool,
      String argumentsPreview,
      String status,
      java.time.Instant expiresAt,
      String continuationState) {}

  private View view(ToolApprovalRepository.Request request) {
    if (request == null) return null;
    return new View(
        request.id(),
        request.projectId(),
        request.sessionId(),
        request.runId(),
        request.tool(),
        request.argumentsPreview(),
        request.status(),
        request.expiresAt(),
        continuations == null
            ? "UNAVAILABLE"
            : continuations.state(request.id()).orElse("UNAVAILABLE"));
  }

  @GetMapping
  public List<View> list(@PathVariable String projectId) {
    return (continuations == null ? repository.list(projectId) : continuations.list(projectId))
        .stream().map(this::view).toList();
  }

  @GetMapping("/{id}")
  public View show(@PathVariable String projectId, @PathVariable String id) {
    return view(repository.get(projectId, id));
  }

  public record Decision(Boolean approved, Boolean continueRun) {
    public Decision(Boolean approved) {
      this(approved, false);
    }
  }

  @PostMapping("/{id}/decision")
  public View decide(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Decision decision) {
    if (decision.approved() == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "approved is required");
    if (Boolean.TRUE.equals(decision.continueRun()) && continuations == null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "Approval continuation unavailable");
    return view(
        continuations == null
            ? repository.decide(projectId, id, decision.approved())
            : continuations.decide(
                projectId, id, decision.approved(), Boolean.TRUE.equals(decision.continueRun())));
  }

  @GetMapping("/{id}/continuation")
  public java.util.Map<String, String> continuation(
      @PathVariable String projectId, @PathVariable String id) {
    var request = repository.get(projectId, id);
    return java.util.Map.of(
        "approvalId",
        request.id(),
        "runId",
        request.runId(),
        "state",
        continuations == null ? "UNAVAILABLE" : continuations.state(id).orElse("UNAVAILABLE"));
  }
}
