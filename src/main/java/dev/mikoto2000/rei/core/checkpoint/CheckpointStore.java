package dev.mikoto2000.rei.core.checkpoint;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 再開に必要な要点を固定したスナップショットを保持する。
 *
 * <p>Task State / Working Set 等の完全コピーではなく、再開に必要な最小情報を保持する。</p>
 */
public class CheckpointStore {

  private final Clock clock;
  private TurnCheckpoint latest;

  public CheckpointStore() {
    this(Clock.systemDefaultZone());
  }

  public CheckpointStore(Clock clock) {
    this.clock = clock;
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
  }

  /** LLM コンテキストに渡す簡潔な表現を組み立てる。 */
  public String renderForPrompt() {
    if (latest == null) {
      return "";
    }
    return latest.renderForPrompt();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }
}
