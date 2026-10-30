package dev.mikoto2000.rei.checkpoint;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 作業の中断と再開で受け渡す保存される再開用の記録を表す。
 *
 * <p>Execution evidence only. No reasoning trace or raw tool output is stored here.
 *
 * @param schemaVersion データ形式形式のバージョン
 * @param revision 改訂番号
 * @param createdAt 作成日時
 * @param taskId タスク識別子
 * @param projectId 所属プロジェクトの識別子
 * @param projectRoot 作業対象のルートディレクトリ
 * @param sessionId 会話の識別子
 * @param originalRunId 元の実行の識別子
 * @param runId 実行の識別子
 * @param resumedFromRunId 再開済み起点実行の識別子
 * @param resumedFromRevision 再開済み起点改訂番号
 * @param request 要求
 * @param instructions 指示一覧
 * @param acceptanceCriteria 受け入れ条件判断基準一覧
 * @param plan 計画
 * @param currentStep 現在手順
 * @param status 状態
 * @param interruptionReason 中断理由
 * @param nextAction 次操作
 * @param evidence 根拠
 * @param operations 操作一覧
 * @param files ファイル一覧
 * @param git Gitの情報
 * @param processes プロセス一覧
 * @param blockers 作業を妨げる課題
 * @param reconciliation 状態の照合結果
 * @param mode 動作モード
 * @param voiceInput 音声入力からの依頼かどうか
 * @param progress 進捗
 * @param taskState 保存された目的・完了済み作業・残件（旧データでは null）
 */
public record PersistentCheckpoint(
    int schemaVersion,
    long revision,
    Instant createdAt,
    String taskId,
    String projectId,
    String projectRoot,
    String sessionId,
    String originalRunId,
    String runId,
    String resumedFromRunId,
    Long resumedFromRevision,
    String request,
    List<String> instructions,
    List<String> acceptanceCriteria,
    List<Map<String, Object>> plan,
    String currentStep,
    String status,
    String interruptionReason,
    String nextAction,
    List<Evidence> evidence,
    List<Operation> operations,
    Map<String, String> files,
    Map<String, String> git,
    List<ProcessRef> processes,
    List<String> blockers,
    CheckpointReconciler.Result reconciliation,
    dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode,
    boolean voiceInput,
    dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState progress,
    dev.mikoto2000.rei.context.TaskStateSnapshot taskState) {
  /** Compatibility constructor for checkpoints without structured task progress. */
  public PersistentCheckpoint(
      int schemaVersion,
      long revision,
      Instant createdAt,
      String taskId,
      String projectId,
      String projectRoot,
      String sessionId,
      String originalRunId,
      String runId,
      String resumedFromRunId,
      Long resumedFromRevision,
      String request,
      List<String> instructions,
      List<String> acceptanceCriteria,
      List<Map<String, Object>> plan,
      String currentStep,
      String status,
      String interruptionReason,
      String nextAction,
      List<Evidence> evidence,
      List<Operation> operations,
      Map<String, String> files,
      Map<String, String> git,
      List<ProcessRef> processes,
      List<String> blockers,
      CheckpointReconciler.Result reconciliation,
      dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode,
      boolean voiceInput,
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState progress) {
    this(
        schemaVersion,
        revision,
        createdAt,
        taskId,
        projectId,
        projectRoot,
        sessionId,
        originalRunId,
        runId,
        resumedFromRunId,
        resumedFromRevision,
        request,
        instructions,
        acceptanceCriteria,
        plan,
        currentStep,
        status,
        interruptionReason,
        nextAction,
        evidence,
        operations,
        files,
        git,
        processes,
        blockers,
        reconciliation,
        mode,
        voiceInput,
        progress,
        null);
  }

  /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public PersistentCheckpoint(
      int schemaVersion,
      long revision,
      Instant createdAt,
      String taskId,
      String projectId,
      String projectRoot,
      String sessionId,
      String originalRunId,
      String runId,
      String resumedFromRunId,
      Long resumedFromRevision,
      String request,
      List<String> instructions,
      List<String> acceptanceCriteria,
      List<Map<String, Object>> plan,
      String currentStep,
      String status,
      String interruptionReason,
      String nextAction,
      List<Evidence> evidence,
      List<Operation> operations,
      Map<String, String> files,
      Map<String, String> git,
      List<ProcessRef> processes,
      List<String> blockers,
      CheckpointReconciler.Result reconciliation,
      dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode,
      boolean voiceInput) {
    this(
        schemaVersion,
        revision,
        createdAt,
        taskId,
        projectId,
        projectRoot,
        sessionId,
        originalRunId,
        runId,
        resumedFromRunId,
        resumedFromRevision,
        request,
        instructions,
        acceptanceCriteria,
        plan,
        currentStep,
        status,
        interruptionReason,
        nextAction,
        evidence,
        operations,
        files,
        git,
        processes,
        blockers,
        reconciliation,
        mode,
        voiceInput,
        null);
  }

  /** 受け取った情報からPersistentCheckpointを作成する。 */
  public PersistentCheckpoint {
    mode = mode == null ? dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE : mode;
    if (schemaVersion < 1
        || revision < 0
        || createdAt == null
        || taskId == null
        || taskId.isBlank()
        || projectId == null
        || projectId.isBlank()
        || projectRoot == null
        || sessionId == null
        || originalRunId == null
        || runId == null
        || request == null
        || status == null) throw new IllegalArgumentException("Invalid checkpoint identity/schema");
    instructions = List.copyOf(instructions);
    acceptanceCriteria = List.copyOf(acceptanceCriteria);
    plan = plan.stream().map(Map::copyOf).toList();
    evidence = List.copyOf(evidence);
    operations = List.copyOf(operations);
    files = Map.copyOf(files);
    git = Map.copyOf(git);
    processes = List.copyOf(processes);
    blockers = List.copyOf(blockers);
  }

  /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public PersistentCheckpoint(
      int schemaVersion,
      long revision,
      Instant createdAt,
      String taskId,
      String projectId,
      String projectRoot,
      String sessionId,
      String originalRunId,
      String runId,
      String resumedFromRunId,
      Long resumedFromRevision,
      String request,
      List<String> instructions,
      List<String> acceptanceCriteria,
      List<Map<String, Object>> plan,
      String currentStep,
      String status,
      String interruptionReason,
      String nextAction,
      List<Evidence> evidence,
      List<Operation> operations,
      Map<String, String> files,
      Map<String, String> git,
      List<ProcessRef> processes,
      List<String> blockers,
      CheckpointReconciler.Result reconciliation,
      dev.mikoto2000.rei.core.chat.AgentRunContext.Mode mode) {
    this(
        schemaVersion,
        revision,
        createdAt,
        taskId,
        projectId,
        projectRoot,
        sessionId,
        originalRunId,
        runId,
        resumedFromRunId,
        resumedFromRevision,
        request,
        instructions,
        acceptanceCriteria,
        plan,
        currentStep,
        status,
        interruptionReason,
        nextAction,
        evidence,
        operations,
        files,
        git,
        processes,
        blockers,
        reconciliation,
        mode,
        false);
  }

  /** 作業の中断と再開における操作状態の選択肢。 */
  public enum OperationStatus {
    /** 実行を計画済みの状態。 */
    PLANNED,
    /** 開始。 */
    STARTED,
    /** 成功。 */
    SUCCEEDED,
    /** 失敗。 */
    FAILED,
    /** 読み取り結果が未確定。版を確認して再取得できる状態。 */
    RETRYABLE,
    /** 不明。 */
    UNKNOWN
  }

  /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public PersistentCheckpoint(
      int schemaVersion,
      long revision,
      Instant createdAt,
      String taskId,
      String projectId,
      String projectRoot,
      String sessionId,
      String originalRunId,
      String runId,
      String resumedFromRunId,
      Long resumedFromRevision,
      String request,
      List<String> instructions,
      List<String> acceptanceCriteria,
      List<Map<String, Object>> plan,
      String currentStep,
      String status,
      String interruptionReason,
      String nextAction,
      List<Evidence> evidence,
      List<Operation> operations,
      Map<String, String> files,
      Map<String, String> git,
      List<ProcessRef> processes,
      List<String> blockers,
      CheckpointReconciler.Result reconciliation) {
    this(
        schemaVersion,
        revision,
        createdAt,
        taskId,
        projectId,
        projectRoot,
        sessionId,
        originalRunId,
        runId,
        resumedFromRunId,
        resumedFromRevision,
        request,
        instructions,
        acceptanceCriteria,
        plan,
        currentStep,
        status,
        interruptionReason,
        nextAction,
        evidence,
        operations,
        files,
        git,
        processes,
        blockers,
        reconciliation,
        null);
  }

  /**
   * 作業の中断と再開で受け渡す根拠を表す。
   *
   * @param origin 起点
   * @param summary 要約
   * @param eventId イベントの識別子
   * @param runId 実行の識別子
   * @param toolCallId ツール呼び出し識別子
   */
  public record Evidence(
      String origin, String summary, String eventId, String runId, String toolCallId) {}

  /**
   * 作業の中断と再開で受け渡す操作を表す。
   *
   * @param toolCallId ツール呼び出し識別子
   * @param toolName ツール名
   * @param status 状態
   * @param eventId イベントの識別子
   * @param runId 実行の識別子
   */
  public record Operation(
      String toolCallId, String toolName, OperationStatus status, String eventId, String runId) {
    /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Operation(String toolCallId, String toolName, OperationStatus status, String eventId) {
      this(toolCallId, toolName, status, eventId, null);
    }

    /** 作業の中断と再開で用いる操作キーを返す。 */
    public String operationKey() {
      return runId == null ? toolCallId : runId + ":" + toolCallId;
    }
  }

  /**
   * 作業の中断と再開で受け渡すプロセス参照先を表す。
   *
   * @param processId プロセス識別子
   * @param pid プロセス識別子
   * @param startedAt 開始日時
   * @param command コマンド
   * @param owner 所有者
   */
  public record ProcessRef(
      String processId, long pid, String startedAt, String command, String owner) {}

  /** 作業の中断と再開で用いる初期を返す。 */
  public static PersistentCheckpoint initial(
      String task, String project, String session, String run, Path root, String request) {
    return new PersistentCheckpoint(
        1,
        0,
        Instant.now(),
        task,
        project,
        root.toString(),
        session,
        run,
        run,
        null,
        null,
        request,
        List.of(),
        List.of(),
        List.of(),
        null,
        "RUNNING",
        null,
        "照合し、未完了・未検証の工程から継続",
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        List.of(),
        List.of(),
        null);
  }

  /** 作業の中断と再開で用いる改訂番号を返す。 */
  public PersistentCheckpoint revision(long number) {
    return new PersistentCheckpoint(
        schemaVersion,
        number,
        Instant.now(),
        taskId,
        projectId,
        projectRoot,
        sessionId,
        originalRunId,
        runId,
        resumedFromRunId,
        resumedFromRevision,
        request,
        instructions,
        acceptanceCriteria,
        plan,
        currentStep,
        status,
        interruptionReason,
        nextAction,
        evidence,
        operations,
        files,
        git,
        processes,
        blockers,
        reconciliation,
        mode,
        voiceInput,
        progress,
        taskState);
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  public PersistentCheckpoint resume(String newRun) {
    return new PersistentCheckpoint(
        schemaVersion,
        revision,
        createdAt,
        taskId,
        projectId,
        projectRoot,
        sessionId,
        originalRunId,
        newRun,
        runId,
        revision,
        request,
        instructions,
        acceptanceCriteria,
        plan,
        currentStep,
        "RUNNING",
        null,
        nextAction,
        evidence,
        CheckpointOperationRecovery.interrupted(operations),
        files,
        git,
        processes,
        blockers,
        reconciliation,
        mode,
        voiceInput,
        progress,
        taskState);
  }
}
