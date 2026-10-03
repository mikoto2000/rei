package dev.mikoto2000.rei.web;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import dev.mikoto2000.rei.workcontext.*;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;

/** Same authenticated /api/v1 boundary as projects and sessions; no mutable UI project state. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class WorkContextController {
  private final WorkContextService service;private final WorkContextGit git;private final WorkContextProperties properties;
  public WorkContextController(WorkContextService service,WorkContextGit git,WorkContextProperties properties) {this.service=service;this.git=git;this.properties=properties;}
  @GetMapping("/api/v1/projects/{projectId}/work-context")
  public WorkContext current(@PathVariable String projectId) {return service.current(projectId).orElseThrow(()->new ResourceNotFoundException("Work Context"));}
  public record Summary(boolean autoPresent,boolean hasContext,String text) {}
  @GetMapping("/api/v1/projects/{projectId}/work-context/summary")
  public Summary summary(@PathVariable String projectId) {
    var project=service.project(projectId);var saved=service.current(projectId).orElse(null);
    return new Summary(properties.autoPresent(),saved!=null,new WorkContextFormatter().summary(saved,saved==null?null:git.capture(project.root(),Instant.now())));
  }
  public record Revision(long revision,Instant updatedAt,int itemCount) {}
  @GetMapping("/api/v1/projects/{projectId}/work-context/history")
  public List<Revision> history(@PathVariable String projectId,@RequestParam(defaultValue="20") int limit) {
    return service.history(projectId,limit).stream().map(c->new Revision(c.revision(),c.updatedAt(),c.items().size())).toList();
  }
  @GetMapping("/api/v1/projects/{projectId}/work-context/history/{revision}")
  public WorkContext revision(@PathVariable String projectId,@PathVariable long revision) {return service.revision(projectId,revision);}
  @PostMapping("/api/v1/sessions/{sessionId}/work-context/update")
  public Map<String,Object> update(@PathVariable String sessionId) {
    return service.update(sessionId,null).<Map<String,Object>>map(c->Map.of("projectId",c.projectId(),"revision",c.revision(),"message","Saved finalized turns"))
        .orElseGet(()->Map.of("message","No finalized turns"));
  }
}
