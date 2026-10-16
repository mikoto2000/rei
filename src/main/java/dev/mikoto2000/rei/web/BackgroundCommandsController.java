package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.BackgroundRunSubmitService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class BackgroundCommandsController {
  private final BackgroundRunSubmitService runs;

  public BackgroundCommandsController(BackgroundRunSubmitService runs) {
    this.runs = runs;
  }

  public record Text(String text) {}

  public record Image(
      String projectId, String prompt, String output, String model, String size, Boolean raw)
      implements StrictApiRequest {}

  public record Summary(String projectId, String url) implements StrictApiRequest {}

  @GetMapping("/api/v1/projects/{projectId}/summaries/latest")
  public Text latest(@PathVariable String projectId) {
    return new Text(runs.latestSummaryForCli(projectId));
  }

  @PostMapping("/api/v1/summaries/cli")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> summarize(
      @RequestBody Summary request) {
    if (request == null) throw new IllegalArgumentException("Summary required");
    return accepted(runs.summaryForCli(request.projectId(), request.url()));
  }

  @PostMapping("/api/v1/images/cli")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> image(
      @RequestBody Image request) {
    if (request == null) throw new IllegalArgumentException("Image required");
    return accepted(
        runs.imageForCli(
            request.projectId(),
            request.prompt(),
            request.output(),
            request.model(),
            request.size(),
            Boolean.TRUE.equals(request.raw())));
  }

  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
