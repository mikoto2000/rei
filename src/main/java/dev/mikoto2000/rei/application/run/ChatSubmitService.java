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
  private final boolean concurrentEnabled;
  public ChatSubmitService(ProjectRegistry projects, SessionRegistry sessions, RunRegistry runs,
      SessionRepository repository, Clock clock, BiConsumer<AgentRunContext, String> dispatch) {
    this(projects, sessions, runs, new SessionLifecycle(repository, clock), dispatch);
  }
  public ChatSubmitService(ProjectRegistry projects, SessionRegistry sessions, RunRegistry runs,
      SessionLifecycle lifecycle, BiConsumer<AgentRunContext, String> dispatch) {
    this(projects, sessions, runs, lifecycle, dispatch, false);
  }
  public ChatSubmitService(ProjectRegistry projects, SessionRegistry sessions, RunRegistry runs,
      SessionLifecycle lifecycle, BiConsumer<AgentRunContext, String> dispatch, boolean concurrentEnabled) {
    this.projects = projects; this.sessions = sessions; this.runs = runs; this.dispatch = dispatch;
    this.lifecycle = lifecycle;
    this.concurrentEnabled = concurrentEnabled;
  }
  public AgentRunContext submit(String message, String projectId, String sessionId) {
    return submit(message, projectId, sessionId, AgentRunContext.Mode.EXCLUSIVE);
  }
  public AgentRunContext submit(String message, String projectId, String sessionId, AgentRunContext.Mode mode) {
    return submit(message,projectId,sessionId,mode,null);
  }
  public AgentRunContext submit(String message,String projectId,String sessionId,AgentRunContext.Mode mode,String key) {
    if (mode == null) mode = AgentRunContext.Mode.EXCLUSIVE;
    if (mode != AgentRunContext.Mode.EXCLUSIVE && !concurrentEnabled)
      throw new IllegalArgumentException("Concurrent conversations are disabled");
    if (message == null || message.isBlank() || projectId == null || projectId.isBlank())
      throw new IllegalArgumentException("message and projectId are required");
    var project = projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    String fingerprint=null;
    if(key!=null)try {fingerprint=dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(java.util.Arrays.asList(projectId,sessionId,message,mode.name())));}
    catch(java.io.IOException error){throw new IllegalStateException("Cannot fingerprint request",error);}
    return lifecycle.submit(project, sessionId, message, AgentRunContext.RequestSource.WEB, mode,false,key,fingerprint, context -> {
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
