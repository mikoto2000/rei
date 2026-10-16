package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class SearchCommandsController {
  private final IntegrationRunSubmitService runs;

  public SearchCommandsController(IntegrationRunSubmitService runs) {
    this.runs = runs;
  }

  public record Search(
      String projectId,
      String sessionId,
      String query,
      Integer vectorTopK,
      Integer webTopK,
      Double threshold,
      String source)
      implements StrictApiRequest {}

  @PostMapping("/api/v1/search/answers")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> search(
      @RequestBody Search request) {
    if (request == null
        || request.projectId() == null
        || request.query() == null
        || request.query().isBlank()
        || request.query().length() > 16384
        || request.query().indexOf('\0') >= 0
        || request.threshold() != null && !Double.isFinite(request.threshold())
        || request.source() != null && request.source().length() > 16384)
      throw new IllegalArgumentException("Invalid search request");
    var context =
        runs.search(
            request.projectId(),
            request.sessionId(),
            request.query(),
            request.vectorTopK() == null ? 3 : request.vectorTopK(),
            request.webTopK() == null ? 5 : request.webTopK(),
            request.threshold(),
            request.source());
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + context.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(context.runId()));
  }
}
