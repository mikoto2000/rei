package dev.mikoto2000.rei.application.session;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.llm.ConversationIds;

/** Shared admission boundary: persist identity before enqueue; reject unknown continuations. */
public final class SessionLifecycle {
  private final SessionRepository repository;
  private final Clock clock;
  private java.util.function.Consumer<AgentRunContext> selected=context->{};
  public void onSelected(java.util.function.Consumer<AgentRunContext> selected){this.selected=selected;}
  public SessionLifecycle(SessionRepository repository, Clock clock) {
    this.repository = repository; this.clock = clock;
  }
  public SessionMetadata validate(String id, String projectId) {
    var session = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Session"));
    if (!session.projectId().equals(projectId)) throw new SessionConflictException();
    return session;
  }
  /** Persist an empty conversation without starting or inheriting a run. */
  public SessionMetadata create(ProjectContext project, String title) {
    synchronized (repository) {
      var metadata = newMetadata(project, title == null || title.isBlank() ? "New session" : title, clock.instant());
      repository.accept(metadata, () -> {});
      return metadata;
    }
  }
  public AgentRunContext submit(ProjectContext project, String sessionId, String message,
      AgentRunContext.RequestSource source, Consumer<AgentRunContext> enqueue) {
    if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    // All entry points sharing this repository serialize validation, metadata update and FIFO admission.
    synchronized (repository) {
      var now = clock.instant();
      SessionMetadata metadata;
      if (sessionId == null) {
        metadata = newMetadata(project, message, now);
        sessionId = metadata.sessionId();
      } else metadata = validate(sessionId, project.id()).touched(now);
      var context = new AgentRunContext(UUID.randomUUID().toString(), sessionId, project.root(), project.id(), source);
      repository.accept(metadata, () -> enqueue.accept(context));
      selected.accept(context);
      return context;
    }
  }
  private SessionMetadata newMetadata(ProjectContext project, String title, java.time.Instant now) {
    var id = project.conversationId(ConversationIds.chat(UUID.randomUUID().toString()));
    return new SessionMetadata(id, project.id(), SessionTitle.from(title), now, now);
  }
}
