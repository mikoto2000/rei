package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.project.ProjectClient;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.BackgroundExecutionPayload;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shell restoration and live rendering share AgentEvent and the same renderer, with different
 * display modes.
 */
@Component
public class ProjectShellActivity implements AgentEventListener {
  private dev.mikoto2000.rei.checkpoint.ResumeOverviewService resumeOverview;
  private dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory resumeSeen =
      new dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory();

  @org.springframework.beans.factory.annotation.Autowired
  public void setResumeOverview(dev.mikoto2000.rei.checkpoint.ResumeOverviewService service) {
    resumeOverview = service;
  }

  private dev.mikoto2000.rei.workcontext.WorkContextPresenter workContext;
  private final java.util.Set<String> workContextSeen = new java.util.HashSet<>();

  @org.springframework.beans.factory.annotation.Autowired
  public void setWorkContext(dev.mikoto2000.rei.workcontext.WorkContextPresenter presenter) {
    workContext = presenter;
  }

  private final ProjectService projects;
  private final ProjectAgentEventStore store;
  private final ProjectRunStateStore states;
  private final WorkingSet workingSet;
  private final ChatMemory memory;
  private ShellEventOutput output;
  private ShellAgentEventRenderer renderer;
  private ShellAgentEventRenderer globalRenderer;
  private String visibleProject;
  private String visibleSession;
  private ProjectClient client;
  private final java.util.Map<String, ShellAgentEventRenderer> renderers =
      new java.util.HashMap<>();
  private ShellAgentEventRenderer.TopicNotificationOptions options =
      ShellAgentEventRenderer.TopicNotificationOptions.summary();

  @Value("${rei.events.recent-limit:20}")
  private int recentLimit = 20;

  private ActiveRunDisplay activeRuns;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setActiveRuns(ActiveRunDisplay activeRuns) {
    this.activeRuns = activeRuns;
  }

  public ProjectShellActivity(
      ProjectService projects,
      ProjectAgentEventStore store,
      ProjectRunStateStore states,
      WorkingSet workingSet,
      ChatMemory memory) {
    this.projects = projects;
    this.store = store;
    this.states = states;
    this.workingSet = workingSet;
    this.memory = memory;
  }

  public synchronized void attach(ShellEventOutput output) {
    attach(output, options);
  }

  public synchronized void attach(
      ShellEventOutput output, ShellAgentEventRenderer.TopicNotificationOptions options) {
    this.output = output;
    this.options = options;
    client = projects.currentClient();
    visibleSession = projects.currentSessionId();
    visibleProject = projects.currentContext().id();
    renderers.clear();
    renderer = rendererFor(visibleProject);
    globalRenderer = new ShellAgentEventRenderer(output, options);
    resumeSeen = new dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory();
    if (resumeOverview != null && resumeOverview.enabled()) {
      try {
        String summary = resumeOverview.startupSummary();
        if (!summary.isBlank()) output.println(summary);
      } catch (RuntimeException error) {
        overviewUnavailable(error);
      }
      presentResume(projects.currentContext());
    }
    presentWorkContext(projects.currentContext(), visibleSession);
  }

  @Override
  public synchronized void onEvent(AgentEvent event) {
    if (renderer == null) return;
    refreshSession();
    if (event.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED) renderer.finish();
    if (event.payload() instanceof BackgroundExecutionPayload payload) {
      renderer.renderBackgroundExecution(
          payload,
          activeRuns == null ? event.projectId() : activeRuns.projectName(event.projectId()));
      return;
    }
    if (visibleProject.equals(event.projectId())
        && event.sessionId() != null
        && !visibleConversation().equals(event.sessionId())) return;
    if (activeRuns != null
        && event.projectId() != null
        && !event.projectId().equals(visibleProject)
        && (event.type() == AgentEventType.AGENT_RUN_COMPLETED
            || event.type() == AgentEventType.AGENT_RUN_FAILED)) {
      renderer.finish();
      output.println("[" + event.type().value() + "] " + activeRuns.projectName(event.projectId()));
      output.flush();
    }
    if (event.projectId() == null) globalRenderer.onEvent(event);
    else rendererFor(event.projectId()).onEvent(event);
  }

  public synchronized void restore(ProjectContext project) {
    if (output == null) return;
    refreshSession(false);
    renderer.finish();
    visibleProject = project.id();
    renderer = rendererFor(visibleProject);
    output.println("Switched project: " + project.name());
    presentResume(project);
    presentWorkContext(project, visibleSession);
    if (activeRuns != null) output.println(activeRuns.summary());
    String conversation =
        visibleSession == null ? project.conversationId("chat:main") : visibleSession;
    output.println(
        "Conversation restored: "
            + (visibleSession == null ? "chat:main" : visibleSession)
            + " ("
            + memory.get(conversation).size()
            + " messages)");
    output.println("Working Set restored: " + workingSet.getFiles().size() + " items");
    if (visibleSession == null)
      output.println(
          "Last run: "
              + states.read(project.id()).map(ProjectRunStateStore.State::status).orElse("none"));
    output.println("Recent activity:");
    for (var event : store.recent(project.id(), Math.max(1, Math.min(recentLimit, 1000))))
      if (event.sessionId() == null || visibleConversation().equals(event.sessionId()))
        renderer.onRecentEvent(event);
    output.flush();
  }

  /**
   * Refresh on command completion and before asynchronous events; never replay a previous run as
   * current.
   */
  public synchronized void refreshSession() {
    refreshSession(true);
  }

  private String visibleConversation() {
    // No selected Session means the legacy Shell conversation, not every client Session.
    return visibleSession == null ? "project:" + visibleProject + ":chat:main" : visibleSession;
  }

  private void refreshSession(boolean showContext) {
    if (client == null || renderer == null) return;
    String selected;
    ProjectContext selectedProject;
    try (var scope = client.open()) {
      selected = projects.currentSessionId();
      selectedProject = projects.currentContext();
    }
    if (java.util.Objects.equals(selected, visibleSession)) return;
    renderer.finish();
    visibleSession = selected;
    if (showContext) presentWorkContext(selectedProject, selected);
    renderers.clear();
    renderer = rendererFor(visibleProject);
  }

  private void presentWorkContext(ProjectContext project, String session) {
    if (workContext == null || output == null) return;
    try {
      workContext.presentSaved(project, session, workContextSeen).ifPresent(output::println);
      output.flush();
    } catch (RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Work Context presentation unavailable ({})", error.getClass().getSimpleName());
    }
  }

  private void presentResume(ProjectContext project) {
    if (resumeOverview == null || !resumeOverview.enabled()) return;
    try {
      String text =
          resumeSeen.present(
              resumeOverview.overview(project.id()),
              resumeOverview.zone(java.time.ZoneId.systemDefault()),
              false);
      if (!text.isBlank()) output.println(text);
      output.flush();
    } catch (RuntimeException error) {
      overviewUnavailable(error);
    }
  }

  private void overviewUnavailable(RuntimeException error) {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn("Resume overview unavailable ({})", error.getClass().getSimpleName());
  }

  private ShellAgentEventRenderer rendererFor(String projectId) {
    return renderers.computeIfAbsent(
        projectId,
        id ->
            new ShellAgentEventRenderer(
                new ShellEventOutput() {
                  public void print(String text) {
                    if (id.equals(visibleProject)) output.print(text);
                  }

                  public void println(String text) {
                    if (id.equals(visibleProject)) output.println(text);
                  }

                  public void flush() {
                    if (id.equals(visibleProject)) output.flush();
                  }

                  public void approvalActions(String project, String session, String approval) {
                    if (id.equals(visibleProject))
                      output.approvalActions(project, session, approval);
                  }

                  public void approvalRequired(String message) {
                    if (id.equals(visibleProject)) output.approvalRequired(message);
                  }
                },
                options));
  }
}
