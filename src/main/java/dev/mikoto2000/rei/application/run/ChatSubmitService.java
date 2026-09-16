package dev.mikoto2000.rei.application.run;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.time.Clock;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.llm.ConversationIds;

public final class ChatSubmitService {
  private final ProjectRegistry projects;
  private final SessionRegistry sessions;
  private final RunRegistry runs;
  private final SessionRepository repository;
  private final Clock clock;
  private final BiConsumer<AgentRunContext, String> dispatch;
  public ChatSubmitService(ProjectRegistry projects, SessionRegistry sessions, RunRegistry runs,
      SessionRepository repository, Clock clock, BiConsumer<AgentRunContext, String> dispatch) {
    this.projects = projects; this.sessions = sessions; this.runs = runs; this.dispatch = dispatch;
    this.repository = repository; this.clock = clock;
  }
  public AgentRunContext submit(String message, String projectId, String sessionId) {
    if (message == null || message.isBlank() || projectId == null || projectId.isBlank())
      throw new IllegalArgumentException("message and projectId are required");
    var project = projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    SessionMetadata metadata;
    var now = clock.instant();
    if (sessionId == null) {
      sessionId = project.conversationId(ConversationIds.chat(UUID.randomUUID().toString()));
      metadata = new SessionMetadata(sessionId, project.id(), SessionTitle.from(message), now, now);
    } else {
      var existing = repository.findById(sessionId).orElseThrow(() -> new ResourceNotFoundException("Session"));
      if (!existing.projectId().equals(project.id())) throw new SessionConflictException();
      metadata = existing.touched(now);
    }
    var context = new AgentRunContext(UUID.randomUUID().toString(), sessionId, project.root(), project.id(),
        AgentRunContext.RequestSource.WEB);
    repository.accept(metadata, () -> {
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
    return context;
  }
}
