package dev.mikoto2000.rei.application.session;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;

/**
 * 会話検索条件のユースケースを実現する。
 *
 * <p>Shared read-only application boundary for Web and Shell.
 */
public final class SessionQueryService {
  /** 会話一覧。 */
  private final SessionRepository sessions;

  /** 履歴。 */
  private final ConversationHistory history;

  /** 続きの取得位置一覧。 */
  private final CursorCodec cursors = new CursorCodec();

  /** 指定された値や利用先を使って、SessionQueryServiceを初期化する。 */
  public SessionQueryService(SessionRepository sessions, ConversationHistory history) {
    this.sessions = sessions;
    this.history = history;
  }

  /** 会話の継続管理で参照する会話を返す。 */
  public SessionMetadata getSession(String id) {
    return sessions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Session"));
  }

  /** 利用可能な対象を確認できるよう、会話一覧を一覧にする。 */
  public HistoryPage<SessionMetadata> listSessions(
      String projectId, Integer requestedLimit, String cursor) {
    int limit = Pagination.limit(requestedLimit);
    if (projectId != null && projectId.isBlank())
      throw new IllegalArgumentException("Invalid projectId");
    String scope = "sessions:" + (projectId == null ? "" : projectId);
    var after = cursors.decode(scope, cursor);
    var rows = sessions.findPage(projectId, after, limit + 1);
    return Pagination.page(
        rows, limit, row -> cursors.encode(scope, new CursorKey(row.updatedAt(), row.sessionId())));
  }

  /** 利用可能な対象を確認できるよう、やり取り一覧を一覧にする。 */
  public HistoryPage<SessionTurn> listTurns(
      String sessionId, Integer requestedLimit, String cursor) {
    int limit = Pagination.limit(requestedLimit);
    String scope = "turns:" + sessionId;
    var after = cursors.decode(scope, cursor);
    getSession(sessionId);
    var rows = history.findTurns(sessionId, after, limit + 1);
    return Pagination.page(
        rows, limit, row -> cursors.encode(scope, new CursorKey(row.createdAt(), row.runId())));
  }
}
