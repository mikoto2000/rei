package dev.mikoto2000.rei.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Run ごとの進捗を入力スレッドから安全に読むための表示用スナップショット。 */
@Component
public final class TaskProgressStore {
  private final Map<String, TaskStateSnapshot> states = new LinkedHashMap<>();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  /** 差分を Run 単位で蓄積する。Project に残る別タスクの工程や保持上限の影響を受けない。 */
  public void record(
      String runId, String goal, String status, List<String> completed, List<String> pending) {
    if (runId == null || runId.isBlank()) return;
    synchronized (states) {
      var previous = states.get(runId);
      var done = new java.util.LinkedHashSet<String>();
      var todo = new java.util.LinkedHashSet<String>();
      if (previous != null && previous.goal().equals(goal)) {
        done.addAll(previous.completed());
        todo.addAll(previous.pending());
      }
      if (completed != null)
        completed.stream().filter(s -> s != null && !s.isBlank()).forEach(done::add);
      if (pending != null)
        pending.stream().filter(s -> s != null && !s.isBlank()).forEach(todo::add);
      todo.removeAll(done);
      // 完了通知だけで未完了工程を消さない。実際に記録された完了工程を数える。
      save(runId, new TaskStateSnapshot(goal, status, List.copyOf(done), List.copyOf(todo)));
    }
    changed();
  }

  /** 実行中の Run の状態だけを記録する。スコープを持たない更新は表示しない。 */
  public void update(String runId, TaskStateSnapshot state) {
    if (runId == null || runId.isBlank()) return;
    synchronized (states) {
      save(runId, state);
    }
    changed();
  }

  private void save(String runId, TaskStateSnapshot state) {
    if ("IDLE".equals(state.status())) states.remove(runId);
    else states.put(runId, state);
    // 異常終了で cleanup が届かない場合にも表示用メモリを制限する。
    while (states.size() > 256) states.remove(states.keySet().iterator().next());
  }

  public TaskStateSnapshot get(String runId) {
    synchronized (states) {
      return states.get(runId);
    }
  }

  public void remove(String runId) {
    boolean removed;
    synchronized (states) {
      removed = states.remove(runId) != null;
    }
    if (removed) changed();
  }

  public AutoCloseable onChange(Runnable listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  private void changed() {
    for (var listener : listeners) {
      try {
        listener.run();
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(TaskProgressStore.class)
            .warn("Task progress display listener failed", error);
      }
    }
  }
}
