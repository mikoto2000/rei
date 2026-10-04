package dev.mikoto2000.rei.web;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.application.state.OperationConflictException;

/** Human answers are saved facts, never permission grants or execution requests. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/dependencies")
public class DependencyController {
  private final PersistentDependencyRepository repository;
  private final DependencyObservationService observations;
  public DependencyController(PersistentDependencyRepository repository,DependencyObservationService observations) {
    this.repository=repository;this.observations=observations;
  }
  @GetMapping public List<PersistentDependencyRepository.Entry> list(@PathVariable String projectId) {return repository.list(projectId);}
  @GetMapping("/{id}") public PersistentDependencyRepository.Entry show(@PathVariable String projectId,@PathVariable String id) {return repository.get(projectId,id);}
  @GetMapping("/{id}/history") public List<PersistentDependencyRepository.Fact> history(@PathVariable String projectId,@PathVariable String id) {return repository.history(projectId,id);}
  public record AnswerRequest(Long expectedVersion,String answer) {}
  @PostMapping("/{id}/answer") public PersistentDependencyRepository.Entry answer(@PathVariable String projectId,@PathVariable String id,@RequestBody AnswerRequest request) {
    if(request==null||request.expectedVersion()==null||request.expectedVersion()<0)throw new IllegalArgumentException("Version required");
    if(PersistentDependencyRepository.terminal(repository.get(projectId,id).state()))throw new OperationConflictException();
    var result=repository.answer(projectId,id,request.answer(),request.expectedVersion());
    observations.flushFacts();return result;
  }
}
