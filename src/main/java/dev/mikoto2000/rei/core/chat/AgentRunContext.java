package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.Objects;

/** Identity and location are captured before dispatch, never resolved at completion. */
public record AgentRunContext(String runId, String conversationId, Path projectRoot, String projectId) {
  public AgentRunContext(String runId, String conversationId, Path projectRoot) {
    this(runId, conversationId, projectRoot, null);
  }
  public AgentRunContext(String runId, dev.mikoto2000.rei.core.project.ProjectContext project, String logicalConversation) {
    this(runId, project.conversationId(logicalConversation), project.root(), project.id());
  }
  public AgentRunContext {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(conversationId);
    projectRoot = Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
  }
}
