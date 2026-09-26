package dev.mikoto2000.rei.application.session;

import java.util.function.BiConsumer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectService;

/** Selection belongs to a Shell client, never to the process or an execution thread. */
public final class ShellConversationService {
  private final ProjectService projects;
  private final SessionLifecycle lifecycle;
  private final BiConsumer<AgentRunContext, String> dispatch;
  public ShellConversationService(ProjectService projects, SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch) {
    this.projects = projects; this.lifecycle = lifecycle; this.dispatch = dispatch;
  }
  public AgentRunContext submit(String message) {
    synchronized (projects.currentClient()) {
      var project = projects.currentContext();
      var context = lifecycle.submit(project, currentSessionId(), message, AgentRunContext.RequestSource.SHELL,
          run -> dispatch.accept(run, message));
      projects.selectSession(context.conversationId());
      return context;
    }
  }
  public void newConversation() { newConversation(null); }
  public SessionMetadata newConversation(String title) {
    synchronized (projects.currentClient()) {
      var session = lifecycle.create(projects.currentContext(), title);
      projects.selectSession(session.sessionId());
      return session;
    }
  }
  public void resume(String sessionId) {
    synchronized (projects.currentClient()) {
      lifecycle.validate(sessionId, projects.currentContext().id());
      projects.selectSession(sessionId);
    }
  }
  public String currentSessionId() { return projects.currentSessionId(); }
}
