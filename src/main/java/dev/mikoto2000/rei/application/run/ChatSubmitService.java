package dev.mikoto2000.rei.application.run;

import java.util.function.BiConsumer;
import java.time.Clock;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;

public final class ChatSubmitService {
  private final ProjectRegistry projects;
  private final SessionRegistry sessions;
  private final RunRegistry runs;
  private final SessionLifecycle lifecycle;
  private final BiConsumer<AgentRunContext, String> dispatch;
  public ChatSubmitService(ProjectRegistry projects, SessionRegistry sessions, RunRegistry runs,
      SessionRepository repository, Clock clock, BiConsumer<AgentRunContext, String> dispatch) {
    this(projects, sessions, runs, new SessionLifecycle(repository, clock), dispatch);
  }
  public ChatSubmitService(ProjectRegistry projects, SessionRegistry sessions, RunRegistry runs,
      SessionLifecycle lifecycle, BiConsumer<AgentRunContext, String> dispatch) {
    this.projects = projects; this.sessions = sessions; this.runs = runs; this.dispatch = dispatch;
    this.lifecycle = lifecycle;
  }
  public AgentRunContext submit(String message, String projectId, String sessionId) {
    if (message == null || message.isBlank() || projectId == null || projectId.isBlank())
      throw new IllegalArgumentException("message and projectId are required");
    var project = projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    return lifecycle.submit(project, sessionId, message, AgentRunContext.RequestSource.WEB, context -> {
      sessions.remember(context.conversationId(), project.id());
      try {
        runs.register(context);
        dispatch.accept(context, message);
      } catch (RuntimeException | Error error) {
        runs.forget(context.runId());
        sessions.forget(context.conversationId());
        throw error;
      }
    });
  }
}
