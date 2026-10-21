package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す目標受付から終了までの管理通知内容を表す。
 *
 * <p>Additive completion phase; old event JSON and Java constructors remain supported.
 *
 * @param goalId 目標識別子
 * @param status 状態
 * @param attempts 試行回数
 * @param maxRuns 最大実行一覧
 * @param llmCallsUsed 言語モデル呼び出し回数使用量
 * @param maxLlmCalls 最大言語モデル呼び出し回数
 * @param reason 理由
 * @param completionPhase 完了条件段階
 * @param stageProgress 段階進捗
 */
public record GoalLifecyclePayload(
    String goalId,
    String status,
    int attempts,
    int maxRuns,
    int llmCallsUsed,
    int maxLlmCalls,
    String reason,
    String completionPhase,
    dev.mikoto2000.rei.goal.GoalStageService.EventProgress stageProgress)
    implements AgentEventPayload {
  /** 実行状況の通知を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public GoalLifecyclePayload(
      String goalId,
      String status,
      int attempts,
      int maxRuns,
      int used,
      int calls,
      String reason,
      String completionPhase) {
    this(goalId, status, attempts, maxRuns, used, calls, reason, completionPhase, null);
  }

  /** 実行状況の通知を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public GoalLifecyclePayload(
      String goalId, String status, int attempts, int maxRuns, int used, int calls, String reason) {
    this(goalId, status, attempts, maxRuns, used, calls, reason, null);
  }

  /** 受け取った情報からGoalLifecyclePayloadを作成する。 */
  public GoalLifecyclePayload {
    if (completionPhase == null)
      completionPhase =
          java.util.Set.of("WAITING_APPROVAL", "PAUSED").contains(status) ? "WAITING" : status;
  }
}
