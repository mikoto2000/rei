package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RunController {
  private final RunService runs;
  public RunController(RunService runs) { this.runs = runs; }
  @GetMapping("/api/v1/runs/{runId}")
  public RunResponse get(@PathVariable String runId) { return RunResponse.from(runs.get(runId)); }
  @PostMapping("/api/v1/runs/{runId}/cancel")
  public org.springframework.http.ResponseEntity<RunResponse> cancel(@PathVariable String runId) {
    var result = runs.cancel(runId);
    return org.springframework.http.ResponseEntity.status(result.accepted() ? 202 : 200)
        .body(RunResponse.from(result.run()));
  }
}
