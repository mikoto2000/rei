package dev.mikoto2000.rei.web;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/checkpoints")
public class CheckpointController {
  private final PersistentCheckpointService service;
  public CheckpointController(PersistentCheckpointService service){this.service=service;}
  @GetMapping public List<PersistentCheckpoint> list(@PathVariable String projectId){return service.list(projectId);}
  public record Text(String text){public Text{text=dev.mikoto2000.rei.event.CredentialRedactor.redact(text);}}
  @GetMapping("/report") public Text report(@PathVariable String projectId){return new Text(String.valueOf(service.list(projectId)));}
  @GetMapping("/report/list") public Text reportList(@PathVariable String projectId){return new Text(String.valueOf(service.list(projectId).stream().map(state->state.taskId()+" ["+state.status()+"] "+PersistentCheckpointService.bounded(state.request(),160)).toList()));}
  @GetMapping("/{taskId}/report") public Text reportShow(@PathVariable String projectId,@PathVariable String taskId){return new Text(service.get(projectId,taskId)+"\n"+service.inspect(projectId,taskId));}
  @GetMapping("/{taskId}") public PersistentCheckpoint show(@PathVariable String projectId,@PathVariable String taskId){return service.get(projectId,taskId);}
  @GetMapping("/{taskId}/reconciliation") public CheckpointReconciler.Result inspect(@PathVariable String projectId,@PathVariable String taskId){return service.inspect(projectId,taskId);}
  public record SaveRequest(String sessionId){}
  @PostMapping public PersistentCheckpoint save(@PathVariable String projectId,@RequestBody SaveRequest request){return service.saveCurrent(projectId,request.sessionId());}
  @PostMapping("/{taskId}/resume") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public PersistentCheckpointService.ResumeResult resume(@PathVariable String projectId,@PathVariable String taskId){return service.resume(projectId,taskId,AgentRunContext.RequestSource.WEB);}
  @PostMapping("/{taskId}/abandon") public PersistentCheckpoint abandon(@PathVariable String projectId,@PathVariable String taskId){return service.abandon(projectId,taskId);}
}
