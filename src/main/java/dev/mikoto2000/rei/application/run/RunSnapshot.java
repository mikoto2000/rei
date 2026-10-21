package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.time.Instant;

/**
 * 実行の受付と状態管理で受け渡す実行状態の記録を表す。
 *
 * @param context 文脈
 * @param status 状態
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 * @param failure 失敗情報
 * @param childOrigin 子の実行を開始した経路
 */
public record RunSnapshot(
    AgentRunContext context,
    RunStatus status,
    Instant startedAt,
    Instant completedAt,
    RunFailure failure,
    ChildOrigin childOrigin) {
  /**
   * 実行の受付と状態管理で受け渡す子の実行を開始した経路を表す。
   *
   * @param parentRunId 親実行の識別子
   * @param sessionId 会話の識別子
   * @param agentId エージェント識別子
   * @param durableTaskId 永続化されたタスク識別子
   */
  public record ChildOrigin(
      String parentRunId, String sessionId, String agentId, String durableTaskId) {
    /** 実行の受付と状態管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public ChildOrigin(String parentRunId, String sessionId, String agentId) {
      this(parentRunId, sessionId, agentId, null);
    }
  }

  /** 実行の受付と状態管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RunSnapshot(
      AgentRunContext context,
      RunStatus status,
      Instant startedAt,
      Instant completedAt,
      RunFailure failure) {
    this(context, status, startedAt, completedAt, failure, null);
  }
}
