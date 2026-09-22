package dev.mikoto2000.rei.ui.shell;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.completion.*;

/** Session IDs are inserted; titles are descriptions, never lookup keys. No history files are read. */
@Component
public class SessionCompletionCandidates implements CompletionMetadata {
  private final SessionRepository sessions;
  public SessionCompletionCandidates() { this(null); }
  @Autowired public SessionCompletionCandidates(SessionRepository sessions) { this.sessions = sessions; }
  @Override public Set<String> types(CompletionContext context) { return Set.of("choices"); }
  protected boolean accepts(String projectId) { return true; }
  @Override public List<CompletionCandidate> choices(CompletionContext context) {
    if (sessions == null) return List.of();
    return sessions.completionSnapshot().stream().filter(row -> accepts(row.projectId()))
        .sorted(Comparator.comparing(dev.mikoto2000.rei.application.session.SessionMetadata::updatedAt).reversed())
        .map(row -> new CompletionCandidate(row.sessionId(), row.sessionId(), row.title(), "session", true)).toList();
  }
  @Component
  public static class CurrentProject extends SessionCompletionCandidates {
    private final ProjectService projects;
    public CurrentProject() { this(null, null); }
    @Autowired public CurrentProject(SessionRepository sessions, ProjectService projects) {
      super(sessions); this.projects = projects;
    }
    @Override protected boolean accepts(String projectId) {
      return projects != null && projects.completionProjects().stream()
          .anyMatch(project -> project.id().equals(projectId) && project.root().equals(projects.currentProject()));
    }
  }
}
