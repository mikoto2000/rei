package dev.mikoto2000.rei.temporal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 時刻と期間の解釈に必要なエージェント実行予定の管理を扱う。 */
public interface AgentScheduler {
  /** 指定された時刻や条件で実行できるよう、変更後を予約する。 */
  ScheduledAgentTask scheduleAfter(Duration duration, String action, String conversationId);

  /** 指定された時刻や条件で実行できるよう、日時を予約する。 */
  ScheduledAgentTask scheduleAt(Instant executeAt, String action, String conversationId);

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  List<ScheduledAgentTask> list();

  /** 対象の実行をキャンセルする。 */
  void cancel(String id);
}
