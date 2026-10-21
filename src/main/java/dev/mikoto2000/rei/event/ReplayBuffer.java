package dev.mikoto2000.rei.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 実行状況の通知に必要な再送一時保持領域を扱う。
 *
 * <p>Accessed under the owning EventBus monitor. Sequence numbers are supplied by that bus.
 */
public final class ReplayBuffer {
  /** 実行状況の通知に必要な履歴を扱う。 */
  private static final class History {
    /** イベント一覧。 */
    final ArrayDeque<AgentEvent> events = new ArrayDeque<>();

    /** evicted終点／最新。 */
    long evictedThrough, latest;

    /** 終端発生順序。 */
    Long terminalSequence;

    /** 完了日時。 */
    Instant completedAt;
  }

  /**
   * 実行状況の通知で受け渡す状態の記録を表す。
   *
   * @param events イベント一覧
   * @param latestSequence 最新発生順序
   * @param terminalSequence 終端発生順序
   */
  public record Snapshot(List<AgentEvent> events, long latestSequence, Long terminalSequence) {}

  /** 実行一覧。 */
  private final Map<String, History> runs = new HashMap<>();

  /** 許容件数。 */
  private final int capacity;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、ReplayBufferを初期化する。 */
  public ReplayBuffer(int capacity, Clock clock) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
    this.clock = clock;
  }

  /** 既存の記録へ経緯を残すため、再送一時保持領域を追記する。 */
  public void append(AgentEvent event) {
    if (event.runId() == null) return;
    var history = runs.computeIfAbsent(event.runId(), ignored -> new History());
    history.events.addLast(event);
    history.latest = event.sequence();
    while (history.events.size() > capacity)
      history.evictedThrough = history.events.removeFirst().sequence();
    if (history.terminalSequence == null
        && (event.type() == AgentEventType.AGENT_RUN_COMPLETED
            || event.type() == AgentEventType.AGENT_RUN_FAILED
            || event.type() == AgentEventType.AGENT_RUN_CANCELLED)) {
      history.terminalSequence = event.sequence();
      history.completedAt = clock.instant();
    }
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  public Snapshot snapshot(String runId, long fromSequence) {
    if (fromSequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
    var history = runs.get(runId);
    if (history == null) return new Snapshot(List.of(), 0, null);
    if (fromSequence < history.evictedThrough) throw new ReplayGapException();
    return new Snapshot(
        history.events.stream().filter(event -> event.sequence() > fromSequence).toList(),
        history.latest,
        history.terminalSequence);
  }

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除実行を処理する。 */
  public void purgeRun(String runId) {
    runs.remove(runId);
  }

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  public void purgeExpired() {
    purgeExpired(Set.of());
  }

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  public void purgeExpired(Set<String> protectedRuns) {
    runs.entrySet()
        .removeIf(
            entry ->
                !protectedRuns.contains(entry.getKey())
                    && entry.getValue().completedAt != null
                    && !clock
                        .instant()
                        .isBefore(entry.getValue().completedAt.plus(Duration.ofMinutes(30))));
  }
}
