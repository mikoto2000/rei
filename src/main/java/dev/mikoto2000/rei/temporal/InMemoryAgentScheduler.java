package dev.mikoto2000.rei.temporal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 時刻と期間の解釈に必要な入力先記憶エージェント実行予定の管理を扱う。
 *
 * <p>Compatibility fixture; production uses PersistentAgentScheduler.
 */
public class InMemoryAgentScheduler implements AgentScheduler {
  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** タスク一覧。 */
  private final List<ScheduledAgentTask> tasks = new CopyOnWriteArrayList<>();

  /** 指定された値や利用先を使って、InMemoryAgentSchedulerを初期化する。 */
  public InMemoryAgentScheduler(Clock clock) {
    this.clock = clock;
  }

  /** 指定された時刻や条件で実行できるよう、変更後を予約する。 */
  @Override
  public ScheduledAgentTask scheduleAfter(Duration duration, String action, String conversationId) {
    Instant createdAt = Instant.now(clock);
    return save(
        new ScheduledAgentTask(
            newId(), createdAt, createdAt.plus(duration), action, conversationId));
  }

  /** 指定された時刻や条件で実行できるよう、日時を予約する。 */
  @Override
  public ScheduledAgentTask scheduleAt(Instant executeAt, String action, String conversationId) {
    return save(
        new ScheduledAgentTask(newId(), Instant.now(clock), executeAt, action, conversationId));
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @Override
  public List<ScheduledAgentTask> list() {
    return List.copyOf(tasks);
  }

  /** 対象の実行をキャンセルする。 */
  @Override
  public void cancel(String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("timerId is required");
    if (!tasks.removeIf(task -> task.id().equals(id)))
      throw new IllegalArgumentException("Schedule not found");
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  private ScheduledAgentTask save(ScheduledAgentTask task) {
    tasks.add(task);
    return task;
  }

  /** 新たな対象を利用できるよう、識別子を作成する。 */
  private String newId() {
    return "timer-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
