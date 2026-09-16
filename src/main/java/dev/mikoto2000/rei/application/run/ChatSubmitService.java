package dev.mikoto2000.rei.application.run;

import java.util.UUID;
import java.util.function.BiConsumer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.llm.ConversationIds;

public final class ChatSubmitService {
  private final ProjectRegistry projects;
  private final SessionRegistry sessions;
  private final RunRegistry runs;
  private final BiConsumer<AgentRunContext, String> dispatch;
  public ChatSubmitService(ProjectRegistry projects, SessionRegistry sessions, RunRegistry runs,
      BiConsumer<AgentRunContext, String> dispatch) {
    this.projects = projects; this.sessions = sessions; this.runs = runs; this.dispatch = dispatch;
  }
  public AgentRunContext submit(String message, String projectId, String sessionId) {
    if (message == null || message.isBlank() || projectId == null || projectId.isBlank())
      throw new IllegalArgumentException("message and projectId are required");
    var project = projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    if (sessionId == null) {
      sessionId = project.conversationId(ConversationIds.chat(UUID.randomUUID().toString()));
      sessions.register(sessionId, project.id());
    } else sessions.requireProject(sessionId, project.id());
    var context = new AgentRunContext(UUID.randomUUID().toString(), sessionId, project.root(), project.id(),
        AgentRunContext.RequestSource.WEB);
    runs.register(context);
    dispatch.accept(context, message);
    return context;
  }
}
