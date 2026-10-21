package dev.mikoto2000.rei.core.checkpoint;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 再開に必要な要点を固定したスナップショットを保持する。
 *
 * <p>Task State / Working Set 等の完全コピーではなく、再開に必要な最小情報を保持する。
 */
public class CheckpointStore {

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 最新。 */
  private TurnCheckpoint latest;

  /** 実行状態の引き継ぎを利用するため、指定を補って共通の初期化へ引き渡す。 */
  public CheckpointStore() {
    this(Clock.systemDefaultZone());
  }

  /** 実行状態の引き継ぎを利用するため、指定を補って共通の初期化へ引き渡す。 */
  public CheckpointStore(Clock clock) {
    this(clock, null, null);
  }

  /** 指定された値や利用先を使って、CheckpointStoreを初期化する。 */
  public CheckpointStore(
      Clock clock, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.clock = clock;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  /** 最新 checkpoint を返す。なければ空。 */
  public Optional<TurnCheckpoint> latest() {
    return Optional.ofNullable(latest);
  }

  /** checkpoint が空かどうか。 */
  public boolean isEmpty() {
    return latest == null;
  }

  /** checkpoint を保存する。最新 1 件を保持する。 */
  public void save(TurnCheckpoint checkpoint) {
    this.latest = checkpoint;
    if (events != null && eventPublisher != null) {
      int workingFileCount =
          checkpoint.workingFiles() == null ? 0 : checkpoint.workingFiles().size();
      eventPublisher.publish(
          events.checkpointSaved(checkpoint.taskId(), checkpoint.reason(), workingFileCount));
    }
  }

  /** LLM コンテキストに渡す簡潔な表現を組み立てる。 */
  public String renderForPrompt() {
    if (latest == null) {
      return "";
    }
    return latest.renderForPrompt();
  }

  /** 実行状態の引き継ぎで用いる現在日時を返す。 */
  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
