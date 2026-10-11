package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

/** Named memory operations with explicit Project ownership; reuses legacy Shell rendering. */
@RestController
@ConditionalOnProperty(name="rei.web.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/memory")
public final class ScopedMemoryController {
  private final ProjectRegistry projects;
  private final MemoryCommandSupport memory;
  public record Text(String text) {}
  public ScopedMemoryController(ProjectRegistry projects,MemoryCommandSupport memory) { this.projects=projects;this.memory=memory; }
  private String project(String id) {
    if(id==null||id.isBlank()||id.length()>128)throw new IllegalArgumentException("Project ID required");
    return projects.resolveById(id).orElseThrow(()->new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project")).id();
  }
  @GetMapping public Text list(@PathVariable String projectId,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
    return new Text(memory.listForProject(project(projectId),limit,offset));
  }
  @GetMapping("/search") public Text search(@PathVariable String projectId,@RequestParam String query,@RequestParam(defaultValue="10") int limit) {
    if(query==null||query.length()>16384)throw new IllegalArgumentException("Invalid memory query");
    return new Text(memory.searchForProject(project(projectId),query,limit));
  }
  @GetMapping("/{id}") public Text show(@PathVariable String projectId,@PathVariable String id) { return new Text(memory.showForProject(project(projectId),id)); }
  @PostMapping("/{id}/forget") public Text forget(@PathVariable String projectId,@PathVariable String id) { return new Text(memory.forgetForProject(project(projectId),id)); }
}
