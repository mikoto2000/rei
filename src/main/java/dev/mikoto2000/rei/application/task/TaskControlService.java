package dev.mikoto2000.rei.application.task;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import java.util.Objects;

/**
 * タスク制御のユースケースを実現する。
 *
 * <p>Human controls delegate to existing source owners and never run a second execution loop.
 */
public final class TaskControlService {
  /** タスク一覧。 */
  private final TaskManagerService tasks;

  /** 会話の実行を受け付ける窓口。 */
  private final ChatSubmitService chats;

  /** 実行一覧。 */
  private final RunService runs;

  /** 登録台帳。 */
  private final RunRegistry registry;

  /** 入力一覧。 */
  private final ConversationInputRouter inputs;

  /** 再開用の記録一覧。 */
  private final PersistentCheckpointService checkpoints;

  /** 目標一覧。 */
  private final GoalLoopService goals;

  /** 依存条件一覧。 */
  private final PersistentDependencyRepository dependencies;

  /** 予約されたエージェント実行を管理する窓口。 */
  private final PersistentAgentScheduler schedules;

  /** 指定された値や利用先を使って、TaskControlServiceを初期化する。 */
  public TaskControlService(
      TaskManagerService tasks,
      ChatSubmitService chats,
      RunService runs,
      RunRegistry registry,
      ConversationInputRouter inputs,
      PersistentCheckpointService checkpoints,
      GoalLoopService goals,
      PersistentDependencyRepository dependencies,
      PersistentAgentScheduler schedules) {
    this.tasks = tasks;
    this.chats = chats;
    this.runs = runs;
    this.registry = registry;
    this.inputs = inputs;
    this.checkpoints = checkpoints;
    this.goals = goals;
    this.dependencies = dependencies;
    this.schedules = schedules;
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public TaskView submit(
      String project, String session, String message, AgentRunContext.Mode mode) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (message == null || message.isBlank() || message.length() > 16384)
      throw new IllegalArgumentException("Invalid Task input");
    var run = chats.submit(message, project, session, mode);
    return tasks.get(run.projectId(), run.conversationId(), "run:" + run.runId());
  }

  /** 対象の実行をキャンセルする。 */
  public TaskView cancel(
      String project, String session, String id, String expectedRun, long revision) {
    if (revision < 0) throw new IllegalArgumentException("Invalid Task revision");
    var task = tasks.get(project, session, id);
    if (task.status().equals("CANCELLED") && Objects.equals(expectedRun, task.runId())) return task;
    expected(task, expectedRun, revision);
    if (!task.cancelSupported()) throw new OperationConflictException();
    switch (task.kind()) {
      case "RUN", "CHECKPOINT", "SUBAGENT" -> {
        owned(task.runId());
        runs.cancel(task.runId());
      }
      case "GOAL" -> {
        if (task.status().equals("RUNNING")) owned(task.runId());
        goals.cancel(project, task.sourceId(), expectedRun, revision);
      }
      case "DEPENDENCY" -> dependencies.cancel(project, task.sourceId(), revision);
      case "SCHEDULE" -> schedules.cancel(project, task.sourceId(), revision);
      default -> throw new OperationConflictException();
    }
    return tasks.get(project, session, id);
  }

  /**
   * 実行中タスクの操作で用いる中断を返す。
   *
   * <p>Stops the current execution while retaining existing Goal/Checkpoint resume state and
   * budgets.
   */
  public TaskView suspend(
      String project, String session, String id, String expectedRun, long revision) {
    var task = tasks.get(project, session, id);
    expected(task, expectedRun, revision);
    if (!task.suspendSupported()) throw new OperationConflictException();
    owned(task.runId());
    runs.cancel(task.runId());
    return tasks.get(project, session, id);
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  public TaskView resume(
      String project, String session, String id, String expectedRun, long revision) {
    var task = tasks.get(project, session, id);
    expected(task, expectedRun, revision);
    if (!task.resumeSupported()) throw new OperationConflictException();
    switch (task.kind()) {
      case "CHECKPOINT" ->
          checkpoints.resume(project, task.sourceId(), AgentRunContext.RequestSource.WEB, revision);
      case "GOAL" -> {
        synchronized (goals) {
          expected(tasks.get(project, session, id), expectedRun, revision);
          try {
            goals.run(project, task.sourceId());
          } catch (IllegalStateException unavailable) {
            throw new OperationConflictException();
          }
        }
      }
      default -> throw new OperationConflictException();
    }
    return tasks.get(project, session, id);
  }

  /**
   * 実行中タスクの操作で用いる入力を返す。
   *
   * <p>Adds bounded input to the selected Run's next iteration; never creates a Run.
   */
  public TaskView input(
      String project,
      String session,
      String id,
      String expectedRun,
      long revision,
      String message) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (message == null || message.isBlank() || message.length() > 16384)
      throw new IllegalArgumentException("Invalid Task guidance");
    var task = tasks.get(project, session, id);
    expected(task, expectedRun, revision);
    if (!task.inputSupported()) throw new OperationConflictException();
    owned(task.runId());
    if (!inputs.offerIntervention(project, session, task.runId(), message))
      throw new OperationConflictException();
    return tasks.get(project, session, id);
  }

  /** 実行中タスクの操作に関する期待する内容を処理する。 */
  private static void expected(TaskView task, String run, long revision) {
    if (revision < 0) throw new IllegalArgumentException("Invalid Task revision");
    if (task.revision() != revision || !Objects.equals(task.runId(), run))
      throw new OperationConflictException();
  }

  /** 実行中タスクの操作に関する自分が所有する対象を処理する。 */
  private void owned(String run) {
    if (run == null || !registry.ownsExecution(run)) throw new OperationConflictException();
  }
}
