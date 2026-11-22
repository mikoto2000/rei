package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.runmanagement.CheckpointCandidate;
import dev.mikoto2000.rei.runmanagement.RunManagement;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only candidates; each row retains the owning project for explicit resume. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "rei.web.enabled",
    havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type =
        org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class CheckpointCandidatesController {
  private final RunManagement service;

  public CheckpointCandidatesController(RunManagement service) {
    this.service = service;
  }

  @GetMapping("/api/v1/checkpoints/candidates")
  public List<CheckpointCandidate> candidates(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String status) {
    return service.candidates(projectId, status);
  }
}
