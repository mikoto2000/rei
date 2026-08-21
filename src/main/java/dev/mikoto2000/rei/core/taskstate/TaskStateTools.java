package dev.mikoto2000.rei.core.taskstate;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * LLM が作業の進捗に応じて Task State を更新するための内部ツール。
 *
 * <p>既存の LLM 呼び出しの中で Task State の更新情報も返させるため、
 * 追加の LLM リクエストを発生させずに状態を更新できる。</p>
 */
@Component
public class TaskStateTools {

  private final TaskState taskState;

  public TaskStateTools(TaskState taskState) {
    this.taskState = taskState;
  }

  TaskState taskState() {
    return taskState;
  }

  /**
   * 現在の作業目的・進捗・次に行うべきことを更新する。
   *
   * @param goal      現在の目的。新しいタスクを開始する場合は設定する。
   * @param status    状態（IDLE / IN_PROGRESS / COMPLETED / BLOCKED）
   * @param completed 完了した作業単位の追加リスト
   * @param pending   次に行う作業単位の追加リスト
   */
  @Tool(name = "updateTaskState", description = """
      現在の作業目的・進捗・次に行うべきことを更新する。
      @param goal      現在の目的。新しいタスクを開始する場合は設定する。
      @param status    状態（IDLE / IN_PROGRESS / COMPLETED / BLOCKED）
      @param completed 完了した作業単位の追加リスト
      @param pending   次に行う作業単位の追加リスト
      """)
  String updateTaskState(String goal, String status, List<String> completed, List<String> pending) {
    if (status == null || status.isBlank()) {
      return "status は必須です";
    }
    switch (status) {
      case TaskState.STATUS_IDLE -> {
        taskState.reset();
      }
      case TaskState.STATUS_IN_PROGRESS -> {
        if (goal != null && !goal.isBlank()) {
          taskState.start(goal);
        } else if (taskState.isEmpty()) {
          return "goal は必須です";
        }
        addAll(taskState, completed, pending);
      }
      case TaskState.STATUS_COMPLETED -> {
        if (goal != null && !goal.isBlank()) {
          taskState.start(goal);
        }
        addAll(taskState, completed, pending);
        taskState.complete();
      }
      case TaskState.STATUS_BLOCKED -> {
        if (goal != null && !goal.isBlank()) {
          taskState.start(goal);
        }
        addAll(taskState, completed, pending);
        taskState.block();
      }
      default -> {
        return "不正な status です: " + status;
      }
    }
    return "updated";
  }

  private void addAll(TaskState taskState, List<String> completed, List<String> pending) {
    if (completed != null) {
      for (String item : completed) {
        taskState.addCompleted(item);
      }
    }
    if (pending != null) {
      for (String item : pending) {
        taskState.addPending(item);
      }
    }
  }
}
