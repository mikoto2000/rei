package dev.mikoto2000.rei.application.input;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.util.Objects;

/** Captured client selection. A null Session is only permitted for a new keyboard conversation. */
public record ConversationTarget(ProjectContext project, String sessionId) {
  public ConversationTarget {
    Objects.requireNonNull(project);
    if (project.id() == null || project.id().isBlank())
      throw new IllegalArgumentException("Project required");
    if (sessionId != null && sessionId.isBlank())
      throw new IllegalArgumentException("Invalid Session");
  }
}
