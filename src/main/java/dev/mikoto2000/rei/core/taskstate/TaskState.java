package dev.mikoto2000.rei.core.taskstate;

import dev.mikoto2000.rei.context.TaskStateSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * 現在の作業目的・進捗・次に行うべきことを保持する。
 *
 * <p>Working Set（どのファイルを使用しているか）とは別の責務として、 「何を目的としているか」「どこまで終わったか」「次に何をするか」を保持する。
 */
public class TaskState {

  /** 現在継続中のタスクがない状態。 */
  public static final String STATUS_IDLE = "IDLE";

  /** 作業中。 */
  public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

  /** 現在のタスクが完了した。 */
  public static final String STATUS_COMPLETED = "COMPLETED";

  /** 外部要因・不足情報・エラー等で進められない。 */
  public static final String STATUS_BLOCKED = "BLOCKED";

  /** 既定最大項目一覧。 */
  static final int DEFAULT_MAX_ITEMS = 20;

  /** 最大項目一覧。 */
  private final int maxItems;

  /** 目標。 */
  private String goal;

  /** 状態。 */
  private String status;

  /** Run that established this goal; used only when projecting automatic context. */
  private String ownerRunId;

  /** Returns the goal's provenance without modifying the saved work. */
  public String ownerRunId() {
    return ownerRunId;
  }

  /** 完了。 */
  private final List<String> completed = new ArrayList<>();

  /** 待機中。 */
  private final List<String> pending = new ArrayList<>();

  /** 作業状態の記録を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public TaskState() {
    this(DEFAULT_MAX_ITEMS);
  }

  /** 指定された値や利用先を使って、TaskStateを初期化する。 */
  public TaskState(int maxItems) {
    this.maxItems = Math.max(1, maxItems);
    this.goal = "";
    this.status = STATUS_IDLE;
  }

  /** 現在の目的。 */
  public String goal() {
    return goal;
  }

  /** 現在の状態。 */
  public String status() {
    return status;
  }

  /** 完了した作業単位の一覧。 */
  public List<String> completed() {
    return List.copyOf(completed);
  }

  /** 次に行う作業単位の一覧。 */
  public List<String> pending() {
    return List.copyOf(pending);
  }

  /** Capture the exact working state for a later Run. */
  public TaskStateSnapshot snapshot() {
    return new TaskStateSnapshot(goal, status, completed, pending);
  }

  /** Restore saved work and bind its automatic context to the new Run. */
  public void restore(TaskStateSnapshot saved) {
    reset();
    start(saved.goal());
    status = saved.status();
    saved.completed().forEach(this::addCompleted);
    saved.pending().forEach(this::addPending);
  }

  /** 目的を設定し、作業中に遷移する。 */
  public void start(String goal) {
    ownerRunId = dev.mikoto2000.rei.context.RunIdentity.currentRunId();
    this.goal = goal == null ? "" : goal;
    this.status = STATUS_IN_PROGRESS;
  }

  /** 状態を変更する。 */
  public void setStatus(String status) {
    this.status = status;
  }

  /** 完了した作業単位を追加する。 */
  public void addCompleted(String item) {
    addUnique(completed, item);
    pending.remove(item);
  }

  /** 次に行う作業単位を追加する。 */
  public void addPending(String item) {
    if (!completed.contains(item)) addUnique(pending, item);
  }

  /** 完了時に pending を空にする。 */
  public void complete() {
    this.status = STATUS_COMPLETED;
    this.pending.clear();
  }

  /** 継続不能時に BLOCKED へ遷移する。 */
  public void block() {
    this.status = STATUS_BLOCKED;
  }

  /** 新しいタスクへ切り替える（状態をリセットする）。 */
  public void reset() {
    this.goal = "";
    this.status = STATUS_IDLE;
    this.completed.clear();
    this.pending.clear();
  }

  /** Task State が空（IDLE かつ goal なし）かどうか。 */
  public boolean isEmpty() {
    return STATUS_IDLE.equals(status) && goal == null || goal.isBlank();
  }

  /** 新たな対象を管理できるよう、一意なを追加する。 */
  private void addUnique(List<String> items, String item) {
    if (item == null || item.isBlank()) {
      return;
    }
    if (!items.contains(item)) {
      items.add(item);
      evictIfNeeded(items);
    }
  }

  /** 作業状態の記録に関する保持対象からの除外条件付きNeededを処理する。 */
  private void evictIfNeeded(List<String> items) {
    while (items.size() > maxItems) {
      items.remove(0);
    }
  }

  /** LLM コンテキストに渡す簡潔な表現を組み立てる。 */
  public String renderForPrompt() {
    if (isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("## Current Task\n\n");
    sb.append("Goal:\n").append(goal).append("\n\n");
    sb.append("Status:\n").append(status).append("\n\n");
    if (!completed.isEmpty()) {
      sb.append("Completed:\n");
      for (String item : completed) {
        sb.append("- ").append(item).append("\n");
      }
      sb.append("\n");
    }
    if (!pending.isEmpty()) {
      sb.append("Pending:\n");
      for (String item : pending) {
        sb.append("- ").append(item).append("\n");
      }
      sb.append("\n");
    }
    return sb.toString();
  }
}
