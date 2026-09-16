package dev.mikoto2000.rei.application.project;

import java.util.List;
import dev.mikoto2000.rei.core.project.ProjectRegistry;

/** Read registered identities without changing the Shell's current project. */
public final class ProjectQueryService {
  private final ProjectRegistry projects;
  public ProjectQueryService(ProjectRegistry projects) { this.projects = projects; }
  public record ProjectSummary(String id, String name) {}
  public List<ProjectSummary> list() {
    return projects.list().stream().map(project -> new ProjectSummary(project.id(), project.name())).toList();
  }
}
