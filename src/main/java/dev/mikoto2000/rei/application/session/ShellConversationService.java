package dev.mikoto2000.rei.application.session;

import java.util.function.BiConsumer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectService;

/** Selection belongs to a Shell client, never to the process or an execution thread. */
public final class ShellConversationService {
  private final ProjectService projects;
  private final SessionLifecycle lifecycle;
  private final BiConsumer<AgentRunContext, String> dispatch;
  private final boolean concurrentEnabled;
  private final dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs;
  public ShellConversationService(ProjectService projects, SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch) {
    this(projects,lifecycle,dispatch,false,null);
  }
  public ShellConversationService(ProjectService projects,SessionLifecycle lifecycle,BiConsumer<AgentRunContext,String> dispatch,
      boolean concurrentEnabled,dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs) {
    this.projects=projects;this.lifecycle=lifecycle;this.dispatch=dispatch;this.concurrentEnabled=concurrentEnabled;this.inputs=inputs;
  }
  public AgentRunContext submit(String message) {
    return submit(message,AgentRunContext.Mode.EXCLUSIVE);
  }
  public AgentRunContext submit(String message,AgentRunContext.Mode mode) {
    if(mode==null)mode=AgentRunContext.Mode.EXCLUSIVE;
    if(mode!=AgentRunContext.Mode.EXCLUSIVE && !concurrentEnabled)throw new IllegalArgumentException("Concurrent conversations are disabled");
    synchronized (projects.currentClient()) {
      var project = projects.currentContext();
      var context = lifecycle.submit(project, currentSessionId(), message, AgentRunContext.RequestSource.SHELL,mode,
          run -> dispatch.accept(run, message));
      projects.selectSession(context.conversationId());
      return context;
    }
  }
  public boolean intervene(String runId,String message) {
    if(!concurrentEnabled || inputs==null)throw new IllegalArgumentException("Concurrent conversations are disabled");
    synchronized(projects.currentClient()) {
      return inputs.offerIntervention(projects.currentContext().id(),currentSessionId(),runId,message);
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
  public SessionMetadata end() {
    synchronized(projects.currentClient()) {
      String id=currentSessionId();if(id==null)return null;
      var session=lifecycle.end(id,projects.currentContext().id());projects.selectSession(null);return session;
    }
  }
}
