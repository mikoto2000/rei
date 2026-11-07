package dev.mikoto2000.rei.core.taskstate;

import dev.mikoto2000.rei.context.TaskProgressStore;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * LLM が作業の進捗に応じて Task State を更新するための内部ツール。
 *
 * <p>既存の LLM 呼び出しの中で Task State の更新情報も返させるため、 追加の LLM リクエストを発生させずに状態を更新できる。
 */
@Component
public class TaskStateTools {

  /** タスク状態。 */
  private final UpdateTask updateProgress;

  @FunctionalInterface
  private interface ProgressRecorder {
    void record(
        String runId, String goal, String status, List<String> completed, List<String> pending);
  }

  private ProgressRecorder progress = (runId, goal, status, completed, pending) -> {};

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setProgress(TaskProgressStore progress) {
    this.progress = progress::record;
  }

  /** 指定された値や利用先を使って、TaskStateToolsを初期化する。 */
  public TaskStateTools(TaskState taskState) {
    this.updateProgress =
        (goal, status, completed, pending) ->
            applyUpdate(taskState, goal, status, completed, pending);
  }

  /**
   * 現在の作業目的・進捗・次に行うべきことを更新する。
   *
   * @param goal 現在の目的。新しいタスクを開始する場合は設定する。
   * @param status 状態（IDLE / IN_PROGRESS / COMPLETED / BLOCKED）
   * @param completed 完了した作業単位の追加リスト
   * @param pending 次に行う作業単位の追加リスト
   */
  @Tool(
      name = "updateTaskState",
      description =
          """
      現在の作業目的・進捗・次に行うべきことを更新する。
      status は必須で、IDLE / IN_PROGRESS / COMPLETED / BLOCKED のいずれかを指定する。
      goal は新しいタスクを開始する場合に指定し、既存タスクの継続では省略できる。
      completed と pending は文字列の JSON 配列。例: {"status":"IN_PROGRESS","completed":["資料の特定"],"pending":["レビュー委譲","指摘の検証"]}。
      作業がなければ [] または項目自体を省略する。カンマ区切りの文字列や JSON 配列を文字列化した値は指定しない。
      複数工程の作業では、着手時に全体の工程を pending に登録し、工程を終えるたびに同じ文言を completed に追加する。
      この記録は利用者向けの全体進捗表示にも使われる。実際に完了した工程だけを completed に記録する。
      """)
  String updateTaskState(
      @ToolParam(required = false, description = "現在の目的。新しいタスクでは指定し、既存タスクの継続では省略可能。") String goal,
      @ToolParam(description = "必須。IDLE / IN_PROGRESS / COMPLETED / BLOCKED のいずれか。") String status,
      @ToolParam(
              required = false,
              description =
                  "完了した作業を追加する文字列の JSON 配列。例: [\"資料の特定\",\"設定の確認\"]。空なら [] または省略。文字列化した配列やカンマ区切り文字列は不可。")
          List<String> completed,
      @ToolParam(
              required = false,
              description =
                  "次に行う作業を追加する文字列の JSON 配列。例: [\"レビュー委譲\",\"指摘の検証\"]。空なら [] または省略。文字列化した配列やカンマ区切り文字列は不可。")
          List<String> pending) {
    if (status == null || status.isBlank()) {
      return "status は必須です";
    }
    var outcome = updateProgress.apply(goal, status, completed, pending);
    if (outcome.error() != null) return outcome.error();
    progress.record(
        dev.mikoto2000.rei.context.RunIdentity.currentRunId(),
        outcome.goal(),
        outcome.status(),
        completed,
        pending);
    return "updated";
  }

  private static UpdateOutcome applyUpdate(
      TaskState taskState,
      String goal,
      String status,
      List<String> completed,
      List<String> pending) {
    switch (status) {
      case TaskState.STATUS_IDLE -> {
        taskState.reset();
      }
      case TaskState.STATUS_IN_PROGRESS -> {
        if (goal != null && !goal.isBlank()) {
          taskState.start(goal);
        } else if (taskState.isEmpty()) {
          return new UpdateOutcome("goal は必須です", null, null);
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
        return new UpdateOutcome("不正な status です: " + status, null, null);
      }
    }
    return new UpdateOutcome(null, taskState.goal(), taskState.status());
  }

  /** 新たな対象を管理できるよう、全対象を追加する。 */
  private static void addAll(TaskState taskState, List<String> completed, List<String> pending) {
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

  private record UpdateOutcome(String error, String goal, String status) {}

  @FunctionalInterface
  private interface UpdateTask {
    UpdateOutcome apply(String goal, String status, List<String> completed, List<String> pending);
  }
}
