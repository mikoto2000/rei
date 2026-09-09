package dev.mikoto2000.rei.ui.shell;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.memory.ChatMemory;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.*;

/** Shell restoration and live rendering share AgentEvent and the same renderer, with different display modes. */
@Component
public class ProjectShellActivity implements AgentEventListener {
  private final ProjectService projects;
  private final ProjectAgentEventStore store;
  private final ProjectRunStateStore states;
  private final WorkingSet workingSet;
  private final ChatMemory memory;
  private ShellEventOutput output;
  private ShellAgentEventRenderer renderer;
  private ShellAgentEventRenderer globalRenderer;
  private String visibleProject;
  private final java.util.Map<String, ShellAgentEventRenderer> renderers = new java.util.HashMap<>();
  private ShellAgentEventRenderer.TopicNotificationOptions options = ShellAgentEventRenderer.TopicNotificationOptions.summary();
  @Value("${rei.events.recent-limit:20}") private int recentLimit = 20;
  private ActiveRunDisplay activeRuns;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setActiveRuns(ActiveRunDisplay activeRuns) { this.activeRuns = activeRuns; }

  public ProjectShellActivity(ProjectService projects, ProjectAgentEventStore store, ProjectRunStateStore states,
      WorkingSet workingSet, ChatMemory memory) {
    this.projects = projects; this.store = store; this.states = states; this.workingSet = workingSet; this.memory = memory;
  }
  public synchronized void attach(ShellEventOutput output) { attach(output, options); }
  public synchronized void attach(ShellEventOutput output, ShellAgentEventRenderer.TopicNotificationOptions options) {
    this.output = output; this.options = options;
    visibleProject = projects.currentContext().id();
    renderers.clear();
    renderer = rendererFor(visibleProject);
    globalRenderer = new ShellAgentEventRenderer(output, options);
  }
  @Override public synchronized void onEvent(AgentEvent event) {
    if (renderer == null) return;
    if(event.payload() instanceof BackgroundExecutionPayload payload) {
      renderer.renderBackgroundExecution(payload,activeRuns==null?event.projectId():activeRuns.projectName(event.projectId()));
      return;
    }
    if (activeRuns != null && event.projectId() != null && !event.projectId().equals(visibleProject)
        && (event.type() == AgentEventType.AGENT_RUN_COMPLETED || event.type() == AgentEventType.AGENT_RUN_FAILED)) {
      renderer.finish();
      output.println("[" + event.type().value() + "] " + activeRuns.projectName(event.projectId()));
      output.flush();
    }
    if (event.projectId() == null) globalRenderer.onEvent(event);
    else rendererFor(event.projectId()).onEvent(event);
  }
  public synchronized void restore(ProjectContext project) {
    if (output == null) return;
    renderer.finish();
    visibleProject = project.id();
    renderer = rendererFor(visibleProject);
    output.println("Switched project: " + project.name());
    if (activeRuns != null) output.println(activeRuns.summary());
    output.println("Conversation restored: chat:main (" + memory.get(project.conversationId("chat:main")).size() + " messages)");
    output.println("Working Set restored: " + workingSet.getFiles().size() + " items");
    output.println("Last run: " + states.read(project.id()).map(ProjectRunStateStore.State::status).orElse("none"));
    output.println("Recent activity:");
    for (var event : store.recent(project.id(), Math.max(1, Math.min(recentLimit, 1000)))) renderer.onRecentEvent(event);
    output.flush();
  }
  private ShellAgentEventRenderer rendererFor(String projectId) {
    return renderers.computeIfAbsent(projectId, id -> new ShellAgentEventRenderer(new ShellEventOutput() {
      public void print(String text) { if (id.equals(visibleProject)) output.print(text); }
      public void println(String text) { if (id.equals(visibleProject)) output.println(text); }
      public void flush() { if (id.equals(visibleProject)) output.flush(); }
    }, options));
  }
}
