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
  public ProjectController(ProjectQueryService projects) { this.projects = projects; }
  @GetMapping("/api/v1/projects")
  public List<ProjectResponse> list() {
    return projects.list().stream().map(project -> new ProjectResponse(project.id(), project.name())).toList();
  }
}
