package dev.mikoto2000.rei.conversation;

import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.application.session.SessionTitle;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.topic.AgentMessage;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 会話履歴と受付記録の管理に必要な作業習慣会話履歴Appenderを扱う。
 *
 * <p>Same notification, two history projections. No generation, evaluation, user activity, or
 * message events.
 */
@Component
public final class BehaviorConversationHistoryAppender {
  /**
   * 会話履歴と受付記録の管理で受け渡す配信先を表す。
   *
   * @param conversationId 会話の識別子
   * @param projectId 所属プロジェクトの識別子
   * @param alreadyRecorded 既にRecorded
   */
  public record Destination(String conversationId, String projectId, boolean alreadyRecorded) {}

  /** ログ一覧。 */
  private final ConversationLogStore logs;

  /** やり取り一覧。 */
  private final ConversationTurnStore turns;

  /** 会話一覧。 */
  private final SessionRepository sessions;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 指定された値や利用先を使って、BehaviorConversationHistoryAppenderを初期化する。 */
  public BehaviorConversationHistoryAppender(
      ConversationLogStore logs,
      ConversationTurnStore turns,
      SessionRepository sessions,
      ProjectService projects) {
    this.logs = logs;
    this.turns = turns;
    this.sessions = sessions;
    this.projects = projects;
  }

  /** 会話履歴と受付記録の管理で用いる公開情報排他制御を返す。 */
  public Object publicationLock() {
    return projects == null ? this : projects.notificationLock();
  }

  /** 後から状況を確認できるよう、作業習慣会話履歴Appenderを記録する。 */
  public Destination capture(AgentMessage message) {
    var existing = logs.findNotification(message.id());
    if (existing.isPresent()) {
      var id = existing.get().conversationId();
      return new Destination(id, ProjectStorage.projectId(id), true);
    }
    if (projects == null) {
      var id = ConversationIds.currentChat();
      return new Destination(id, ProjectStorage.projectId(id), false);
    }
    var selection = projects.notificationSelection();
    var project = selection.project();
    var id =
        selection.conversationId() == null
            ? project.conversationId(ConversationIds.chat())
            : selection.conversationId();
    return new Destination(id, project.id(), false);
  }

  /** 既存の記録へ経緯を残すため、作業習慣会話履歴Appenderを追記する。 */
  public void append(Destination destination, AgentMessage message) {
    // Metadata is allowlisted. Activity evidence, screenshots and model output are never copied.
    var metadata = new LinkedHashMap<String, String>();
    for (var key : List.of("severity", "triggerType"))
      if (message.metadata().containsKey(key)) metadata.put(key, message.metadata().get(key));
    var entry =
        logs.appendNotification(
            destination.conversationId(),
            message.id(),
            message.content(),
            message.createdAt(),
            metadata);
    String projectId = ProjectStorage.projectId(entry.conversationId());
    if (sessions != null && projectId != null)
      synchronized (sessions) {
        var at = entry.timestamp().toInstant();
        var metadataRow =
            sessions
                .findById(entry.conversationId())
                .map(s -> s.touched(at))
                .orElseGet(
                    () ->
                        new SessionMetadata(
                            entry.conversationId(),
                            projectId,
                            SessionTitle.from(entry.content()),
                            at,
                            at));
        // 別のプロジェクトの情報を、この作業の対象として扱わせない。
        if (!metadataRow.projectId().equals(projectId))
          throw new IllegalArgumentException("Session ownership mismatch");
        sessions.accept(metadataRow, () -> {});
      }
    if (turns != null) turns.appendAssistantNotification(entry);
    if (projects != null && projectId != null)
      projects.selectNotificationDefault(projectId, entry.conversationId());
  }
}
