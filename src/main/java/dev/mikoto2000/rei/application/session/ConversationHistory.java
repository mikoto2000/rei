package dev.mikoto2000.rei.application.session;

import java.util.List;

/**
 * 会話の継続管理に必要な会話履歴を扱う。
 *
 * <p>Durable turns in createdAt ASC, runId ASC order. No dependency on realtime replay.
 */
public interface ConversationHistory {
  /** 対象を特定できるよう、やり取り一覧を探す。 */
  List<SessionTurn> findTurns(String sessionId, CursorKey after, int fetchLimit);
}
