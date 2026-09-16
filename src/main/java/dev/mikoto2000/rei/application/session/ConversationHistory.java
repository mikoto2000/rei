package dev.mikoto2000.rei.application.session;

import java.util.List;

/** Durable turns in createdAt ASC, runId ASC order. No dependency on realtime replay. */
public interface ConversationHistory {
  List<SessionTurn> findTurns(String sessionId, CursorKey after, int fetchLimit);
}
