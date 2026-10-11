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
  private final dev.mikoto2000.rei.core.project.ProjectRegistry projects;
  private final dev.mikoto2000.rei.core.policy.ToolPermissionGuard guard;
  public DependencyController(PersistentDependencyRepository repository,DependencyObservationService observations) {
    this(repository,observations,null,null);
  }
  @org.springframework.beans.factory.annotation.Autowired
  public DependencyController(PersistentDependencyRepository repository,DependencyObservationService observations,
      dev.mikoto2000.rei.core.project.ProjectRegistry projects,dev.mikoto2000.rei.core.policy.ToolPermissionGuard guard) {
    this.repository=repository;this.observations=observations;this.projects=projects;this.guard=guard;
  }
  @GetMapping public List<PersistentDependencyRepository.Entry> list(@PathVariable String projectId) {return repository.list(projectId);}
  @GetMapping("/{id}") public PersistentDependencyRepository.Entry show(@PathVariable String projectId,@PathVariable String id) {return repository.get(projectId,id);}
  @GetMapping("/{id}/history") public List<PersistentDependencyRepository.Fact> history(@PathVariable String projectId,@PathVariable String id) {return repository.history(projectId,id);}
  public record AnswerRequest(Long expectedVersion,String answer) {}
  public record HumanAnswer(String value) implements StrictApiRequest {}
  @PostMapping("/{id}/check") public PersistentDependencyRepository.Entry check(@PathVariable String projectId,@PathVariable String id) {
    var project=project(projectId);var entry=repository.get(projectId,id);boolean network=entry.spec().network();
    var owner=new dev.mikoto2000.rei.core.chat.AgentRunContext(entry.id(),entry.sessionId(),project.root(),projectId,dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB);
    guard.check(network?"checkHttpDependency":"checkDependency","{\"dependencyId\":\""+entry.id()+"\"}",owner);
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)){var result=observations.inspect(projectId,id,network);observations.flushFacts();return result;}
  }
  @PostMapping("/{id}/cancel") public PersistentDependencyRepository.Entry cancel(@PathVariable String projectId,@PathVariable String id) {
    project(projectId);var entry=repository.get(projectId,id);var result=repository.cancel(projectId,id,entry.version());observations.flushFacts();return result;
  }
  @PostMapping("/{id}/human-answer") public PersistentDependencyRepository.Entry humanAnswer(@PathVariable String projectId,@PathVariable String id,@RequestBody HumanAnswer request) {
    project(projectId);if(request==null)throw new IllegalArgumentException("Human answer required");
    var entry=repository.get(projectId,id);repository.answer(projectId,id,request.value(),entry.version());
    var result=observations.inspect(projectId,id,false);observations.flushFacts();return result;
  }
  private dev.mikoto2000.rei.core.project.ProjectContext project(String id){if(projects==null||guard==null)throw new IllegalStateException("Dependency controls unavailable");return projects.resolveById(id).orElseThrow(()->new IllegalArgumentException("Unknown registered Project"));}
  @PostMapping("/{id}/answer") public PersistentDependencyRepository.Entry answer(@PathVariable String projectId,@PathVariable String id,@RequestBody AnswerRequest request) {
    if(request==null||request.expectedVersion()==null||request.expectedVersion()<0)throw new IllegalArgumentException("Version required");
    if(PersistentDependencyRepository.terminal(repository.get(projectId,id).state()))throw new OperationConflictException();
    var result=repository.answer(projectId,id,request.answer(),request.expectedVersion());
    observations.flushFacts();return result;
  }
}
