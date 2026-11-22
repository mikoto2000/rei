package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.runmanagement.RunCheckpointMenu.Checkpoint;
import dev.mikoto2000.rei.runmanagement.RunCheckpointMenu.Run;
import dev.mikoto2000.rei.runmanagement.RunManagement;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/run-management")
public class RunManagementController {
  private final RunManagement service;

  public RunManagementController(RunManagement service) {
    this.service = service;
  }

  @GetMapping
  public List<Run> runs() {
    return service.runs();
  }

  @GetMapping("/history")
  public List<dev.mikoto2000.rei.runmanagement.RunHistoryEntry> history(
      @org.springframework.web.bind.annotation.RequestParam(required = false) String projectId,
      @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
    return service.history(projectId, status, limit);
  }

  @GetMapping("/{projectId}/{runId}/checkpoints")
  public List<Checkpoint> checkpoints(@PathVariable String projectId, @PathVariable String runId) {
    return service.checkpoints(projectId, runId);
  }

  public record DiscardRequest(long revision) {}

  @PostMapping("/{projectId}/{runId}/checkpoints/{taskId}/discard")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void discard(
      @PathVariable String projectId,
      @PathVariable String runId,
      @PathVariable String taskId,
      @RequestBody DiscardRequest request) {
    service.discard(projectId, runId, taskId, request.revision());
  }
}
