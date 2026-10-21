package dev.mikoto2000.rei.application.session;

import java.util.Optional;

/**
 * 会話を保存・検索するための窓口。
 *
 * <p>Persistent source of truth, independent of runtime session retention.
 */
public interface SessionRepository {
  /** 会話の継続管理に関する再確認入力補完状態の記録を処理する。 */
  default void refreshCompletionSnapshot() {}

  /**
   * 会話の継続管理で用いる入力補完状態の記録を返す。
   *
   * <p>Already loaded metadata only. Implementations must not perform I/O for completion.
   */
  default java.util.List<SessionMetadata> completionSnapshot() {
    return java.util.List.of();
  }

  /** 対象を特定できるよう、別識別子を探す。 */
  Optional<SessionMetadata> findById(String sessionId);

  /**
   * 対象を特定できるよう、ページを探す。
   *
   * <p>At most fetchLimit rows, ordered by updatedAt DESC, sessionId ASC, strictly after the key.
   */
  java.util.List<SessionMetadata> findPage(String projectId, CursorKey after, int fetchLimit);

  /**
   * 要求を後続の処理へ引き渡すため、会話保存と検索の窓口を受け付ける。
   *
   * <p>Persist before enqueue; restore previous metadata if synchronous admission fails. The
   * callback must only admit work, never wait for asynchronous execution. Existing
   * identity/title/createdAt are immutable and updatedAt is monotonic.
   */
  void accept(SessionMetadata metadata, Runnable enqueue);
}
