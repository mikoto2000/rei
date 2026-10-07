package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunService;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RunController {
  private final RunService runs;
  private final dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs;
  private final boolean concurrentEnabled;
  public RunController(RunService runs) { this(runs,null,false); }
  @org.springframework.beans.factory.annotation.Autowired
  public RunController(RunService runs,dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs,
      @org.springframework.beans.factory.annotation.Value("${rei.conversation.concurrent-enabled:false}") boolean concurrentEnabled) {
    this.runs=runs;this.inputs=inputs;this.concurrentEnabled=concurrentEnabled;
  }
  public record InputRequest(String projectId,String sessionId,String message) implements StrictApiRequest {}
  @PostMapping("/api/v1/runs/{runId}/input")
  public org.springframework.http.ResponseEntity<Void> input(@PathVariable String runId,@RequestBody InputRequest request) {
    if(!concurrentEnabled)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
    if(request.projectId()==null || request.sessionId()==null || request.message()==null || request.message().isBlank()
        || request.message().length()>16384)throw new IllegalArgumentException("Invalid guidance");
    var run=runs.get(runId);
    if(!request.projectId().equals(run.context().projectId()) || !request.sessionId().equals(run.context().conversationId()))
      throw new dev.mikoto2000.rei.application.run.RunNotFoundException();
    if(run.status()!=dev.mikoto2000.rei.application.run.RunStatus.RUNNING
        || !inputs.offerIntervention(request.projectId(),request.sessionId(),runId,request.message()))
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
    return org.springframework.http.ResponseEntity.accepted().build();
  }
  @GetMapping("/api/v1/runs/{runId}")
  public RunResponse get(@PathVariable String runId) { return RunResponse.from(runs.get(runId)); }
  @PostMapping("/api/v1/runs/{runId}/cancel")
  public org.springframework.http.ResponseEntity<RunResponse> cancel(@PathVariable String runId) {
    var result = runs.cancel(runId);
    return org.springframework.http.ResponseEntity.status(result.accepted() ? 202 : 200)
        .body(RunResponse.from(result.run()));
  }
}
