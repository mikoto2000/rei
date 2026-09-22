package dev.mikoto2000.rei.application.session;

import java.util.Optional;

/** Persistent source of truth, independent of runtime session retention. */
public interface SessionRepository {
  /** Already loaded metadata only. Implementations must not perform I/O for completion. */
  default java.util.List<SessionMetadata> completionSnapshot() { return java.util.List.of(); }
  Optional<SessionMetadata> findById(String sessionId);
  /** At most fetchLimit rows, ordered by updatedAt DESC, sessionId ASC, strictly after the key. */
  java.util.List<SessionMetadata> findPage(String projectId, CursorKey after, int fetchLimit);

  /** Persist before enqueue; restore previous metadata if synchronous admission fails.
   * The callback must only admit work, never wait for asynchronous execution.
   * Existing identity/title/createdAt are immutable and updatedAt is monotonic.
   */
  void accept(SessionMetadata metadata, Runnable enqueue);
}
