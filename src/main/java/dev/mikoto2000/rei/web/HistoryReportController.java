package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.conversation.HistorySearchScope;
import dev.mikoto2000.rei.conversation.HistoryShellService;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.ui.shell.HistoryCommand;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/history")
public final class HistoryReportController {
  private final HistoryCommand report;
  private final SessionQueryService sessions;
  private final ProjectService selection;
  private final ProjectRegistry projects;

  public record Text(String text) {}

  public HistoryReportController(
      HistoryShellService history,
      SessionQueryService sessions,
      ProjectService selection,
      ProjectRegistry projects) {
    this.report = new HistoryCommand(history, sessions, selection);
    this.sessions = sessions;
    this.selection = selection;
    this.projects = projects;
  }

  private Text selected(String projectId, String sessionId, Supplier<String> action) {
    if (projectId == null || projectId.isBlank() || projectId.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    var project =
        projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    if (sessionId != null && !sessions.getSession(sessionId).projectId().equals(project.id()))
      throw new IllegalArgumentException("Session belongs to another Project");
    try (var scope = selection.newClient().open()) {
      selection.cd(project.root().toString());
      selection.selectSession(sessionId);
      return new Text(action.get());
    }
  }

  @GetMapping("/report")
  public Text sessions(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return new Text(report.reportSessions(projectId, limit, cursor));
  }

  @GetMapping("/show")
  public Text show(
      @RequestParam String selectedProjectId,
      @RequestParam(required = false) String conversation,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    if (conversation == null && cursor != null)
      throw new IllegalArgumentException("--cursor requires a session ID");
    return selected(
        selectedProjectId, null, () -> report.reportSession(conversation, limit, cursor));
  }

  @GetMapping("/conversations")
  public Text conversations(
      @RequestParam String selectedProjectId,
      @RequestParam(required = false) String project,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return selected(
        selectedProjectId, null, () -> report.reportConversations(project, limit, offset));
  }

  @GetMapping("/conversation")
  public Text conversation(
      @RequestParam String selectedProjectId,
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) String project,
      @RequestParam(required = false) String conversation,
      @RequestParam(defaultValue = "50") int last,
      @RequestParam(defaultValue = "false") boolean all) {
    return selected(
        selectedProjectId,
        sessionId,
        () -> report.reportConversation(project, conversation, last, all));
  }

  @GetMapping("/search")
  public Text search(
      @RequestParam String selectedProjectId,
      @RequestParam String query,
      @RequestParam(defaultValue = "CURRENT_PROJECT_PREFERRED") HistorySearchScope scope,
      @RequestParam(defaultValue = "10") int limit) {
    return selected(selectedProjectId, null, () -> report.reportSearch(query, scope, limit));
  }
}
