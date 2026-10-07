package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.Objects;

/** Identity and location are captured before dispatch, never resolved at completion. */
public record AgentRunContext(String runId, String conversationId, Path projectRoot, String projectId,
    RequestSource requestSource, Mode mode) {
  public enum RequestSource { SHELL, WEB }
  public enum Mode { EXCLUSIVE, READ_ONLY, CONVERSATION }

  public AgentRunContext(String runId, String conversationId, Path projectRoot, String projectId,
      RequestSource requestSource) {
    this(runId, conversationId, projectRoot, projectId, requestSource, Mode.EXCLUSIVE);
  }

  public AgentRunContext(String runId, String conversationId, Path projectRoot, String projectId) {
    this(runId, conversationId, projectRoot, projectId, RequestSource.SHELL);
  }
  public AgentRunContext(String runId, String conversationId, Path projectRoot) {
    this(runId, conversationId, projectRoot, null);
  }
  public AgentRunContext(String runId, dev.mikoto2000.rei.core.project.ProjectContext project, String logicalConversation) {
    this(runId, project.conversationId(logicalConversation), project.root(), project.id());
  }
  public AgentRunContext {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(conversationId);
    Objects.requireNonNull(requestSource);
    if (mode == null) mode = Mode.EXCLUSIVE; // Legacy persisted ownership records have no mode.
    projectRoot = Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
  }
}
