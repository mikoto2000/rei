package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RunController {
  private final RunService runs;
  public RunController(RunService runs) { this.runs = runs; }
  @GetMapping("/api/v1/runs/{runId}")
  public RunResponse get(@PathVariable String runId) { return RunResponse.from(runs.get(runId)); }
}
