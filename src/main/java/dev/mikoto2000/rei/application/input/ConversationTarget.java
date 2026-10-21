package dev.mikoto2000.rei.application.input;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.util.Objects;

/**
 * 会話入力の受付で受け渡す会話対象を表す。
 *
 * <p>Captured client selection. A null Session is only permitted for a new keyboard conversation.
 *
 * @param project プロジェクト
 * @param sessionId 会話の識別子
 */
public record ConversationTarget(ProjectContext project, String sessionId) {
  /** 受け取った情報からConversationTargetを作成する。 */
  public ConversationTarget {
    Objects.requireNonNull(project);
    if (project.id() == null || project.id().isBlank())
      throw new IllegalArgumentException("Project required");
    if (sessionId != null && sessionId.isBlank())
      throw new IllegalArgumentException("Invalid Session");
  }
}
