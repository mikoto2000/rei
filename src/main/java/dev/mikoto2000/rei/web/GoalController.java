package dev.mikoto2000.rei.web;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.application.state.OperationConflictException;

@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/goals")
public class GoalController {
 private final GoalRepository repository;private final GoalLoopService loop;
 public GoalController(GoalRepository repository,GoalLoopService loop){this.repository=repository;this.loop=loop;}
 @GetMapping public List<GoalRepository.Goal> list(@PathVariable String projectId){return repository.list(projectId);}
 @GetMapping("/{id}") public GoalRepository.Goal show(@PathVariable String projectId,@PathVariable String id){return repository.get(projectId,id);}
 public record History(List<GoalRepository.History> history,List<GoalRepository.Attempt> attempts) {}
 @GetMapping("/{id}/history") public History history(@PathVariable String projectId,@PathVariable String id){return new History(repository.history(projectId,id),repository.attempts(projectId,id));}
 @PostMapping("/{id}/verify") public GoalLoopService.Inspection verify(@PathVariable String projectId,@PathVariable String id){return control(()->loop.verify(projectId,id));}
 @PutMapping("/{id}/completion") public GoalRepository.Goal defineCompletionJson(@PathVariable String projectId,@PathVariable String id,@RequestBody String json){return defineCompletion(projectId,id,GoalRepository.parseCompletion(json));}
 public GoalRepository.Goal defineCompletion(String projectId,String id,GoalCompletionGate.Definition definition){return control(()->loop.defineCompletion(projectId,id,definition));}
 @PostMapping("/{id}/completion-evidence") public GoalRepository.Goal attachCompletionJson(@PathVariable String projectId,@PathVariable String id,@RequestBody String json){return attachCompletion(projectId,id,GoalRepository.parseCompletionProof(json));}
 public GoalRepository.Goal attachCompletion(String projectId,String id,GoalCompletionGate.Proof proof){return control(()->loop.attachCompletion(projectId,id,proof));}
 @PostMapping("/{id}/run") public GoalRepository.Goal run(@PathVariable String projectId,@PathVariable String id){return control(()->loop.run(projectId,id));}
 @PostMapping("/{id}/cancel") public GoalRepository.Goal cancel(@PathVariable String projectId,@PathVariable String id){return control(()->loop.cancel(projectId,id));}
 public record ReconcileRequest(String expectedRunId,boolean acknowledgeUncertainSideEffects) {}
 @PostMapping("/{id}/reconcile") public GoalRepository.Goal reconcile(@PathVariable String projectId,@PathVariable String id,@RequestBody ReconcileRequest request){
  if(request==null||!request.acknowledgeUncertainSideEffects()||request.expectedRunId()==null||request.expectedRunId().isBlank())throw new IllegalArgumentException("Inspect effects and acknowledge the exact saved Run before reconciliation");
  return control(()->loop.reconcile(projectId,id,request.expectedRunId().equals("none")?null:request.expectedRunId()));
 }
 private static <T> T control(Supplier<T> action){try{return action.get();}catch(IllegalStateException conflict){throw new OperationConflictException();}}
}
