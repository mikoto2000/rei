package dev.mikoto2000.rei.application.session;

import java.util.function.BiConsumer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectService;

/** Selection belongs to a Shell client, never to the process or an execution thread. */
public final class ShellConversationService {
  private final dev.mikoto2000.rei.application.input.ConversationInputGateway gateway;
  private final java.time.Clock clock;
  private final ProjectService projects;
  private final SessionLifecycle lifecycle;
  private final boolean concurrentEnabled;
  private final dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs;
  public ShellConversationService(ProjectService projects, SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch) {
    this(projects,lifecycle,dispatch,false,null);
  }
  public ShellConversationService(ProjectService projects,SessionLifecycle lifecycle,BiConsumer<AgentRunContext,String> dispatch,
      boolean concurrentEnabled,dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs) {
    this(projects,lifecycle,dispatch,concurrentEnabled,inputs,java.time.Clock.systemUTC());
  }
  public ShellConversationService(ProjectService projects,SessionLifecycle lifecycle,BiConsumer<AgentRunContext,String> dispatch,
      boolean concurrentEnabled,dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs,java.time.Clock clock) {
    this.clock=java.util.Objects.requireNonNull(clock);
    this.projects=projects;this.lifecycle=lifecycle;this.concurrentEnabled=concurrentEnabled;this.inputs=inputs;
    this.gateway=new dev.mikoto2000.rei.application.input.ConversationInputGateway(lifecycle,dispatch,inputs,clock);
  }
  public AgentRunContext submit(String message) {
    return submit(message,AgentRunContext.Mode.EXCLUSIVE);
  }
  public AgentRunContext submit(String message,AgentRunContext.Mode mode) {
    if(mode==null)mode=AgentRunContext.Mode.EXCLUSIVE;
    if(mode!=AgentRunContext.Mode.EXCLUSIVE && !concurrentEnabled)throw new IllegalArgumentException("Concurrent conversations are disabled");
    synchronized (projects.currentClient()) {
      var project = projects.currentContext();
      var input = new dev.mikoto2000.rei.application.input.ConversationInput(java.util.UUID.randomUUID(),
          dev.mikoto2000.rei.application.input.InputSource.KEYBOARD,
          new dev.mikoto2000.rei.application.input.ConversationTarget(project,currentSessionId()),message,clock.instant());
      var context = gateway.submit(input,mode);
      projects.selectSession(context.conversationId());
      return context;
    }
  }
  /** Captured on the interactive thread, then safe to pass to an ASR worker. */
  public dev.mikoto2000.rei.application.input.ConversationTarget captureTarget() {
    synchronized(projects.currentClient()) {
      if(currentSessionId()==null)newConversation();
      return new dev.mikoto2000.rei.application.input.ConversationTarget(projects.currentContext(),currentSessionId());
    }
  }
  public AgentRunContext submit(dev.mikoto2000.rei.application.input.ConversationInput input) {
    return gateway.submit(input,AgentRunContext.Mode.EXCLUSIVE);
  }
  public java.util.List<dev.mikoto2000.rei.application.input.ConversationInput> pending(
      dev.mikoto2000.rei.application.input.ConversationTarget target) { return gateway.pending(target); }
  public boolean cancelPending(dev.mikoto2000.rei.application.input.ConversationTarget target,java.util.UUID inputId) {
    return gateway.cancelPending(target,inputId);
  }
  public void onPendingCancellation(java.util.function.Predicate<AgentRunContext> cancellation) { gateway.onCancel(cancellation); }
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
