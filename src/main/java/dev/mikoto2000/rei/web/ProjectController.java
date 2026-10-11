package dev.mikoto2000.rei.web;

import java.util.List;
import dev.mikoto2000.rei.application.project.ProjectQueryService;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectController {
  private final ProjectQueryService projects;
  private dev.mikoto2000.rei.core.project.ProjectRegistry registry;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setRegistry(dev.mikoto2000.rei.core.project.ProjectRegistry registry){this.registry=registry;}
  public ProjectController(ProjectQueryService projects) { this.projects = projects; }
  @GetMapping("/api/v1/projects")
  public List<ProjectResponse> list() {
    return projects.list().stream()
        .map(project -> new ProjectResponse(project.id(), project.name(), project.path())).toList();
  }
  public record RegisterRequest(String path) implements StrictApiRequest {}
  @PostMapping("/api/v1/projects/register")
  public ProjectResponse register(@RequestBody RegisterRequest request) {
    if(request==null||request.path()==null||request.path().isBlank()||request.path().length()>4096)throw new IllegalArgumentException("Invalid Project path");
    if(registry==null)throw new IllegalStateException("Project registry unavailable");
    var path=java.nio.file.Path.of(request.path());if(!path.isAbsolute())throw new IllegalArgumentException("Absolute Project path required");
    var project=registry.resolve(path);return new ProjectResponse(project.id(),project.name(),project.root().toString());
  }
}
