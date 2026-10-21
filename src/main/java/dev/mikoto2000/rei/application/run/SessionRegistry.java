package dev.mikoto2000.rei.application.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 会話を登録して管理し、識別子から対象を参照できるようにする。
 *
 * <p>Runtime cache only. Expiration never removes the persistent session identity.
 */
public final class SessionRegistry {
  /**
   * 実行の受付と状態管理で受け渡す会話を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param accessedAt accessed日時
   */
  private record Session(String projectId, Instant accessedAt) {}

  /** 会話一覧。 */
  private final Map<String, Session> sessions = new HashMap<>();

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、SessionRegistryを初期化する。 */
  public SessionRegistry(Clock clock) {
    this.clock = clock;
  }

  /** 実行の受付と状態管理に関するrememberを処理する。 */
  public synchronized void remember(String id, String projectId) {
    purgeExpired();
    var existing = sessions.get(id);
    if (existing != null && !existing.projectId().equals(projectId))
      throw new SessionConflictException();
    sessions.put(id, new Session(projectId, clock.instant()));
  }

  /** 実行の受付と状態管理に関する記憶の削除を処理する。 */
  public synchronized void forget(String id) {
    sessions.remove(id);
  }

  /** 後から対象を参照できるよう、会話登録台帳を登録する。 */
  public synchronized void register(String sessionId, String projectId) {
    purgeExpired();
    if (sessions.putIfAbsent(sessionId, new Session(projectId, clock.instant())) != null)
      throw new IllegalArgumentException("Duplicate session");
  }

  /** 条件を満たさない操作を拒否するため、プロジェクトの前提条件を確認する。 */
  public synchronized String requireProject(String sessionId, String projectId) {
    purgeExpired();
    var session = sessions.get(sessionId);
    if (session == null) throw new ResourceNotFoundException("Session");
    // 別のプロジェクトの情報を、この作業の対象として扱わせない。
    if (!session.projectId().equals(projectId)) throw new SessionConflictException();
    sessions.put(sessionId, new Session(projectId, clock.instant()));
    return projectId;
  }

  /** 実行の受付と状態管理に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  public synchronized void purgeExpired() {
    sessions
        .values()
        .removeIf(
            session ->
                !clock.instant().isBefore(session.accessedAt().plus(Duration.ofMinutes(30))));
  }
}
