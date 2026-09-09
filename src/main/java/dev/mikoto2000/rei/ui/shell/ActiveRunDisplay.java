package dev.mikoto2000.rei.ui.shell;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;

@Component
public class ActiveRunDisplay {
  private final ConversationInputRouter runs;
  private final ProjectService projects;
  private final Clock clock;
  public ActiveRunDisplay(ConversationInputRouter runs, ProjectService projects, Clock clock) {
    this.runs = runs; this.projects = projects; this.clock = clock;
  }
  public String promptStatus() { return "[" + ActiveRun.summary(projects.currentContext().name()) + "] [" + runs.activeRuns().size() + " running]"; }
  public String summary() { return "Active runs: " + runs.activeRuns().size() + " (/runs for details)"; }
  public String projectName(String id) {
    var all = projects.registeredProjects();
    var project = all.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    if (project == null) return id == null ? "unscoped" : id;
    String name = ActiveRun.summary(project.name());
    return all.stream().filter(p -> p.name().equals(project.name())).count() > 1 ? name + " [" + id.substring(0,8) + "]" : name;
  }
  public List<String> rows() {
    return runs.activeRuns().stream().map(run -> {
      long seconds = Math.max(0, Duration.between(run.startedAt(), clock.instant()).toSeconds());
      return projectName(run.projectId()) + "  " + run.status() + "  " + String.format("%02d:%02d", seconds / 60, seconds % 60)
          + "  " + run.requestSummary();
    }).toList();
  }
}
