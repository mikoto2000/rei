package dev.mikoto2000.rei.web;
import dev.mikoto2000.rei.application.run.BackgroundRunSubmitService;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class BackgroundRunController {
  private final BackgroundRunSubmitService runs;
  public BackgroundRunController(BackgroundRunSubmitService runs) { this.runs=runs; }
  public record SummaryRequest(String projectId,String url) {}
  public record ImageRequest(String projectId,String prompt,String size) {}
  public record AcceptedRunResponse(String runId) {}
  @PostMapping("/summaries") public ResponseEntity<AcceptedRunResponse> summary(@RequestBody SummaryRequest r) {
    return accepted(runs.summary(r.projectId(),r.url()).runId());
  }
  @PostMapping("/images") public ResponseEntity<AcceptedRunResponse> image(@RequestBody ImageRequest r) {
    return accepted(runs.image(r.projectId(),r.prompt(),r.size()).runId());
  }
  private ResponseEntity<AcceptedRunResponse> accepted(String id) {
    return ResponseEntity.accepted().location(URI.create("/api/v1/runs/"+id)).body(new AcceptedRunResponse(id));
  }
}
