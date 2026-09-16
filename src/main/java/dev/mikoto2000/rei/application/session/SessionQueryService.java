package dev.mikoto2000.rei.application.session;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;

/** Shared read-only application boundary for Web and Shell. */
public final class SessionQueryService {
  private final SessionRepository sessions;
  private final CursorCodec cursors = new CursorCodec();
  public SessionQueryService(SessionRepository sessions) { this.sessions = sessions; }
  public SessionMetadata getSession(String id) {
    return sessions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Session"));
  }
  public HistoryPage<SessionMetadata> listSessions(String projectId, Integer requestedLimit, String cursor) {
    int limit = Pagination.limit(requestedLimit);
    if (projectId != null && projectId.isBlank()) throw new IllegalArgumentException("Invalid projectId");
    String scope = "sessions:" + (projectId == null ? "" : projectId);
    var after = cursors.decode(scope, cursor);
    var rows = sessions.findPage(projectId, after, limit + 1);
    return Pagination.page(rows, limit, row -> cursors.encode(scope, new CursorKey(row.updatedAt(), row.sessionId())));
  }
}
