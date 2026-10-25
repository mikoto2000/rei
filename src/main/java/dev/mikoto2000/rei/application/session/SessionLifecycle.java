package dev.mikoto2000.rei.application.session;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.run.SessionConflictException;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.llm.ConversationIds;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 会話の所属を検証し、会話情報の保存と実行の受付を共通の境界で管理する。
 *
 * <p>Shared admission boundary: persist identity before enqueue; reject unknown continuations.
 */
public final class SessionLifecycle {
  /** 会話情報の保存先。所属検証と受付を一貫して行うための共有境界でもある。 */
  private final SessionRepository repository;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  private org.springframework.context.ApplicationEventPublisher inputEvents;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setInputEvents(org.springframework.context.ApplicationEventPublisher publisher) {
    inputEvents = publisher;
  }

  /** 重複受付防止キーと実行の受付結果を永続化する台帳。 */
  private dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions;

  /** 会話の継続管理で使用する受付台帳を設定する。 */
  public void setAdmissions(dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions) {
    this.admissions = admissions;
  }

  /** 会話の選択をクライアントへ反映する通知先。 */
  private java.util.function.Consumer<AgentRunContext> selected = context -> {};

  /** クライアントによる会話の利用終了を伝える通知先。 */
  private java.util.function.Consumer<SessionMetadata> ended = session -> {};

  /** クライアントが会話の利用を終えたときに呼び出す処理を登録する。 */
  public void onEnded(java.util.function.Consumer<SessionMetadata> ended) {
    this.ended = ended;
  }

  /** 会話が選択されたときにクライアントの選択状態へ反映する処理を登録する。 */
  public void onSelected(java.util.function.Consumer<AgentRunContext> selected) {
    this.selected = selected;
  }

  /** 指定された値や利用先を使って、SessionLifecycleを初期化する。 */
  public SessionLifecycle(SessionRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** 指定された会話が存在し、操作対象のプロジェクトに所属することを確認する。 */
  public SessionMetadata validate(String id, String projectId) {
    var session =
        repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Session"));
    // 別のプロジェクトに属する会話への操作を拒否し、履歴と作業先の混同を防ぐ。
    if (!session.projectId().equals(projectId)) throw new SessionConflictException();
    return session;
  }

  /**
   * 会話の履歴と再開可能な状態を保ちながら、クライアント側の利用を終了する。
   *
   * <p>Ends the client's activity, while keeping the Session resumable and its history intact.
   */
  public SessionMetadata end(String id, String projectId) {
    if (projectId == null || projectId.isBlank())
      throw new IllegalArgumentException("Project ID is required");
    synchronized (repository) {
      var session = validate(id, projectId);
      // 会話の履歴を残したまま、クライアントによる利用の終了を通知する。
      ended.accept(session);
      return session;
    }
  }

  /**
   * 実行を開始せずに空の会話を保存し、後から入力を受け付けられるようにする。
   *
   * <p>Persist an empty conversation without starting or inheriting a run.
   */
  public SessionMetadata create(ProjectContext project, String title) {
    synchronized (repository) {
      var metadata =
          newMetadata(
              project, title == null || title.isBlank() ? "New session" : title, clock.instant());
      repository.accept(metadata, () -> {});
      return metadata;
    }
  }

  /** 会話の所属と受付条件を確認し、会話情報を保存したうえで入力を実行待ちへ渡す。 */
  public AgentRunContext submit(
      ProjectContext project,
      String sessionId,
      String message,
      AgentRunContext.RequestSource source,
      Consumer<AgentRunContext> enqueue) {
    return submit(project, sessionId, message, source, AgentRunContext.Mode.EXCLUSIVE, enqueue);
  }

  /** 会話の所属と受付条件を確認し、会話情報を保存したうえで入力を実行待ちへ渡す。 */
  public AgentRunContext submit(
      ProjectContext project,
      String sessionId,
      String message,
      AgentRunContext.RequestSource source,
      AgentRunContext.Mode mode,
      Consumer<AgentRunContext> enqueue) {
    return submit(project, sessionId, message, source, mode, false, enqueue);
  }

  /** 会話の所属と受付条件を確認し、会話情報を保存したうえで入力を実行待ちへ渡す。 */
  public AgentRunContext submit(
      ProjectContext project,
      String sessionId,
      String message,
      AgentRunContext.RequestSource source,
      AgentRunContext.Mode mode,
      boolean voiceInput,
      Consumer<AgentRunContext> enqueue) {
    return submit(project, sessionId, message, source, mode, voiceInput, null, null, enqueue);
  }

  /** 会話の所属と受付条件を確認し、会話情報を保存したうえで入力を実行待ちへ渡す。 */
  public AgentRunContext submit(
      ProjectContext project,
      String sessionId,
      String message,
      AgentRunContext.RequestSource source,
      AgentRunContext.Mode mode,
      boolean voiceInput,
      String key,
      String fingerprint,
      Consumer<AgentRunContext> enqueue) {
    return submit(
        project, sessionId, message, source, mode, voiceInput, key, fingerprint, null, enqueue);
  }

  /** 会話の所属と受付条件を確認し、会話情報を保存したうえで入力を実行待ちへ渡す。 */
  public AgentRunContext submit(
      ProjectContext project,
      String sessionId,
      String message,
      AgentRunContext.RequestSource source,
      AgentRunContext.Mode mode,
      boolean voiceInput,
      String key,
      String fingerprint,
      dev.mikoto2000.rei.mcp.McpAdmission mcp,
      Consumer<AgentRunContext> enqueue) {
    if (message == null || message.isBlank())
      throw new IllegalArgumentException("message is required");
    // 入力元が異なっても、所属確認・会話情報の更新・実行受付の順序を共通の境界で守る。
    synchronized (repository) {
      var now = clock.instant();
      SessionMetadata metadata;
      // 会話の指定がなければ新規に作成し、継続の場合は所属を確認して利用日時を更新する。
      if (sessionId == null) {
        metadata = newMetadata(project, message, now);
        sessionId = metadata.sessionId();
      } else metadata = validate(sessionId, project.id()).touched(now);
      // 音声入力と会話ごとの指定から、今回の依頼に適用する応答形式を決める。
      var context =
          new AgentRunContext(
              UUID.randomUUID().toString(),
              sessionId,
              project.root(),
              project.id(),
              source,
              mode,
              voiceInput,
              metadata.responseStyle() == dev.mikoto2000.rei.core.chat.ResponseStyle.AUTO
                  ? (voiceInput
                      ? dev.mikoto2000.rei.core.chat.ResponseStyle.CONVERSATION
                      : dev.mikoto2000.rei.core.chat.ResponseStyle.NORMAL)
                  : metadata.voiceOnly() && !voiceInput
                      ? dev.mikoto2000.rei.core.chat.ResponseStyle.NORMAL
                      : metadata.responseStyle());
      // 重複受付を保証する要求は、受付記録を永続化できる場合だけ受け付ける。
      if (admissions == null) {
        if (key != null || mcp != null)
          throw new IllegalStateException("Durable admission is unavailable");
        repository.accept(metadata, () -> enqueue.accept(context));
      } else {
        var receipt = admissions.accept(metadata, context, key, fingerprint, mcp);
        // 同じ要求が再送された場合は、受け付け済みの実行を再利用する。
        if (receipt.replay()) return receipt.context();
        repository.refreshCompletionSnapshot();
        try {
          enqueue.accept(context);
        } catch (RuntimeException | Error error) {
          if (mcp != null)
            admissions.mcpAdmissionFailed(
                context.runId(),
                error instanceof RuntimeException runtime ? runtime : new IllegalStateException());
          // 受付後に実行へ渡せなかった要求を失敗として確定し、会話の利用枠を戻す。
          admissions.transition(context.runId(), RunStatus.FAILED);
          admissions.release(context.runId());
          throw error;
        }
      }
      // 外部からのMCP呼び出しで、利用者が画面上で選んだ会話を切り替えない。
      if (source != AgentRunContext.RequestSource.MCP) selected.accept(context);
      if (inputEvents != null) {
        try {
          inputEvents.publishEvent(
              new ConversationAccepted(
                  context.projectId(), context.conversationId(), context.runId(), message));
        } catch (RuntimeException failure) {
          org.slf4j.LoggerFactory.getLogger(getClass())
              .warn("Input observer failed: {}", failure.getClass().getSimpleName());
        }
      }
      return context;
    }
  }

  /** 対象プロジェクトの会話に応答形式を保存し、以後の入力に適用する。 */
  public SessionMetadata responseStyle(
      ProjectContext project,
      String sessionId,
      dev.mikoto2000.rei.core.chat.ResponseStyle style,
      boolean voiceOnly) {
    java.util.Objects.requireNonNull(style);
    synchronized (repository) {
      var metadata =
          validate(sessionId, project.id()).withResponseStyle(style, voiceOnly, clock.instant());
      repository.accept(metadata, () -> {});
      return metadata;
    }
  }

  /** プロジェクトに属する新しい会話の識別情報と作成日時を用意する。 */
  private SessionMetadata newMetadata(ProjectContext project, String title, java.time.Instant now) {
    var id = project.conversationId(ConversationIds.chat(UUID.randomUUID().toString()));
    return new SessionMetadata(id, project.id(), SessionTitle.from(title), now, now);
  }
}
