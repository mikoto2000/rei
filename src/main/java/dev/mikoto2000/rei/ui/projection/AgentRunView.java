package dev.mikoto2000.rei.ui.projection;

import java.time.Instant;

/**
 * 実行状況の表示用整理で受け渡すエージェント実行表示内容を表す。
 *
 * @param runId 実行の識別子
 * @param status 状態
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 * @param durationMillis 継続時間ミリ秒数
 * @param error エラー
 */
public record AgentRunView(
    String runId,
    AgentRunStatus status,
    Instant startedAt,
    Instant completedAt,
    Long durationMillis,
    ErrorView error) {

  /** 実行状況の表示用整理で用いる操作がない状態を返す。 */
  public static AgentRunView idle() {
    return new AgentRunView(null, AgentRunStatus.IDLE, null, null, null, null);
  }
}
