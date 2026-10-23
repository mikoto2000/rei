package dev.mikoto2000.rei.core.stagnation;

import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.REPLAN_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.STAGNATED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOOL_ARGUMENT_REPAIR_EXHAUSTED;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一回の実行で使った予算と進捗を保持し、実行を継続できるか判断するための文脈。
 *
 * <p>One user run, explicitly passed in ToolContext across reactive scheduler boundaries.
 */
public class RunExecutionContext {
  /** 情報の取得限定上限。 */
  private static final int ACQUISITION_ONLY_LIMIT = 32;

  /** Separate outcome horizon: forward pagination remains locally productive. */
  private static final int GOAL_IDLE_LIMIT = 96;

  /** 不明判定を猶予する繰り返し回数。 */
  private static final int UNKNOWN_GRACE_ITERATIONS = 16;

  /**
   * 実行の停滞への対処で受け渡す進捗状態を表す。
   *
   * @param information 情報
   * @param budget 予算
   * @param acquisitionOnlyIterations 情報の取得限定繰り返し回数
   * @param unknownIterations 不明繰り返し回数
   * @param unknownReplanNotices 不明計画の見直し案内の回数
   */
  public record ProgressState(
      InformationLedger.State information,
      OutputLimitRunBudget.Snapshot budget,
      int acquisitionOnlyIterations,
      int unknownIterations,
      int unknownReplanNotices,
      Continuity continuity) {
    public ProgressState(
        InformationLedger.State information,
        OutputLimitRunBudget.Snapshot budget,
        int acquisitionOnlyIterations,
        int unknownIterations,
        int unknownReplanNotices) {
      this(
          information,
          budget,
          acquisitionOnlyIterations,
          unknownIterations,
          unknownReplanNotices,
          null);
    }

    /** 実行の停滞への対処を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public ProgressState(
        InformationLedger.State information,
        OutputLimitRunBudget.Snapshot budget,
        int acquisitionOnlyIterations,
        int unknownIterations) {
      this(information, budget, acquisitionOnlyIterations, unknownIterations, 0);
    }

    /** A user-requested retry clears only the consecutive streak and its pending request latch. */
    public ProgressState forExplicitResume() {
      var c = continuity;
      if (c == null) return this;
      var d = c.detector();
      if (d == null) return this;
      return new ProgressState(
          information,
          budget,
          acquisitionOnlyIterations,
          unknownIterations,
          unknownReplanNotices,
          new Continuity(
              new StagnationDetector.State(
                  0, d.replanCount(), false, d.lastToolFingerprint(), d.lastFailureFingerprint()),
              c.evaluator(),
              c.goalIdleIterations(),
              c.coverageIterations(),
              c.lastProgress(),
              c.recoveries(),
              c.reason()));
    }
  }

  /** Additive checkpoint data; old checkpoints use the conservative legacy fallback. */
  public record Continuity(
      StagnationDetector.State detector,
      ProgressEvaluator.State evaluator,
      int goalIdleIterations,
      int coverageIterations,
      ProgressEvidence lastProgress,
      List<String> recoveries,
      String reason) {
    public Continuity {
      recoveries = List.copyOf(recoveries);
      if (goalIdleIterations < 0 || coverageIterations < 0 || recoveries.size() > 2)
        throw new IllegalArgumentException("Invalid continuity state");
    }
  }

  private int goalIdleIterations;
  private int coverageIterations;
  private ProgressEvidence lastProgress;
  private final List<String> recoveries = new ArrayList<>();
  private boolean restorationIteration;

  /** Compact evidence for Activity Timeline and terminal failure diagnostics. */
  public record Diagnosis(
      boolean localStagnant,
      boolean goalStagnant,
      int goalIdleIterations,
      int goalThreshold,
      int coverageIterations,
      String currentStep,
      ProgressEvidence lastProgress,
      String reason,
      List<String> recentActions,
      List<String> recoveries,
      int replanBudget,
      List<String> remainingSteps) {
    public Diagnosis {
      recentActions = List.copyOf(recentActions);
      recoveries = List.copyOf(recoveries);
      remainingSteps = List.copyOf(remainingSteps);
    }
  }

  public synchronized Diagnosis diagnosis() {
    boolean legacyGoal = "WORK_STALLED".equals(stagnationReason) && detector.isStagnant();
    return new Diagnosis(
        detector.isStagnant() && !legacyGoal,
        goalIdleIterations >= GOAL_IDLE_LIMIT || legacyGoal,
        goalIdleIterations,
        GOAL_IDLE_LIMIT,
        coverageIterations,
        java.util.Objects.toString(evaluator.currentStep(), "unstructured"),
        lastProgress,
        stagnationReason,
        List.copyOf(recentActions),
        recoveries,
        budget.snapshot().stagnationReplans(),
        evaluator.remainingSteps());
  }

  private String recoveryGuidance() {
    return switch (stagnationReason) {
      case "REPEATED_READ" -> "取得済み情報で判断を整理し、次の未読範囲へ進んでください。再読が必要なら理由を明確にしてください。";
      case "COVERAGE_WITHOUT_WORK", "WORK_STALLED" -> "取得済み情報から中間成果物を作り、工程の完了条件と追加調査の必要性を確認してください。";
      case "CONTEXT_RESTORATION_FAILED" ->
          "復元対象とバージョンを再確認し、既存の要約・保存済み情報を利用してください。復元できない情報を明示してください。";
      default -> "現在工程の阻害要因と完了条件を確認し、実行方法を変更して再計画してください。";
    };
  }

  private ExecutionStoppedException stagnationStop(ExecutionStoppedException.Reason reason) {
    var diagnosis = diagnosis();
    emit(AgentEventType.STAGNATION_STOPPED, null, reason.name());
    return new ExecutionStoppedException(
        reason,
        CredentialRedactor.redact(
            "reason="
                + stagnationReason
                + ", local="
                + diagnosis.localStagnant()
                + ", goal="
                + diagnosis.goalStagnant()
                + ", step="
                + diagnosis.currentStep()
                + ", lastProgress="
                + lastProgress
                + ", actions="
                + diagnosis.recentActions()
                + ", recoveries="
                + recoveries
                + ", replanBudget="
                + diagnosis.replanBudget()
                + ", remainingSteps="
                + diagnosis.remainingSteps()
                + ". "
                + recoveryGuidance()
                + " 保存済み成果と権限を確認し、/resume list から再開対象を確認してください。消費済み予算は引き継ぎます。"));
  }

  /** 実行の停滞への対処で用いる進捗状態の記録を返す。 */
  public synchronized ProgressState progressSnapshot() {
    return new ProgressState(
        evaluator.informationSnapshot(),
        budget.snapshot(),
        acquisitionOnlyIterations,
        unknownIterations,
        unknownReplanNotices,
        new Continuity(
            detector.snapshot(),
            evaluator.snapshot(),
            goalIdleIterations,
            coverageIterations,
            lastProgress,
            recoveries,
            stagnationReason));
  }

  /** 保存済みの状態を引き継ぐため、進捗を復元する。 */
  public synchronized void restoreProgress(ProgressState saved) {
    if (saved == null) {
      evaluator.restoreInformation(null);
      return;
    }
    if (saved.acquisitionOnlyIterations() < 0
        || saved.unknownIterations() < 0
        || saved.unknownReplanNotices() < 0
        || saved.unknownReplanNotices() > 2)
      throw new IllegalArgumentException("Invalid saved progress");
    evaluator.restoreInformation(saved.information());
    budget.restore(saved.budget());
    acquisitionOnlyIterations = saved.acquisitionOnlyIterations();
    unknownIterations = saved.unknownIterations();
    unknownReplanNotices = saved.unknownReplanNotices();
    if (saved.continuity() != null) {
      var continuity = saved.continuity();
      detector.restore(continuity.detector());
      evaluator.restore(continuity.evaluator());
      goalIdleIterations = continuity.goalIdleIterations();
      coverageIterations = continuity.coverageIterations();
      lastProgress = continuity.lastProgress();
      recoveries.clear();
      recoveries.addAll(continuity.recoveries());
      if (continuity.reason() != null) stagnationReason = continuity.reason();
    }
  }

  /** Keep detector details inside the execution boundary. */
  public synchronized boolean needsProgressReconciliation() {
    return detector.isStagnant();
  }

  /** Reconcile a previously ignored, host-confirmed skill result without replenishing budgets. */
  public synchronized boolean reconcileSkillProgress(String result) {
    checkActive();
    var evidence =
        evaluator.afterTool(
            "readSkillResource", "{}", result, evaluator.beforeTool("readSkillResource", "{}"));
    var newlyAccounted =
        evidence.stream().filter(e -> e.kind() == ProgressEvent.NEW_INFORMATION).findFirst();
    if (newlyAccounted.isEmpty()) return false;
    detector.recordIteration(true);
    lastProgress = newlyAccounted.get();
    emit(AgentEventType.PROGRESS_DETECTED, lastProgress, "historical_skill_progress_reconciled");
    return true;
  }

  private boolean checkpointRestorationStarted;

  /** Start the existing bounded restoration only on an explicit checkpoint continuation. */
  public synchronized void beginContextRestoration() {
    checkpointRestorationStarted = true;
    evaluator.beginContextRestoration();
  }

  /** A depleted resumed execution must use its handed-off evidence before broad discovery. */
  public synchronized boolean needsResumeWorkRecovery() {
    return checkpointRestorationStarted
        && runContext != null
        && runContext.mode() == dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE
        && detector.isStagnant()
        && evaluator.snapshot().restorationReads() >= 8
        && budget.remainingStagnationReplans() == 0;
  }

  /** 継続入力。 */
  private dev.mikoto2000.rei.core.chat.RunContinuation continuation;

  /** 再開メッセージ一覧。 */
  private List<org.springframework.ai.chat.messages.Message> resumeMessages = List.of();

  /** 実行の停滞への対処で使用する継続入力を設定する。 */
  public void setContinuation(dev.mikoto2000.rei.core.chat.RunContinuation continuation) {
    this.continuation = continuation;
  }

  /** 待機中承認ツール一覧。 */
  private dev.mikoto2000.rei.core.chat.RunContinuation.PendingTools pendingApprovalTools;

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  public void resume(dev.mikoto2000.rei.core.chat.RunContinuation.Saved saved) {
    if (saved != null) {
      budget.restore(saved.budget());
      evaluator.restoreInformation(null);
      resumeMessages = List.copyOf(saved.messages());
      pendingApprovalTools = saved.pendingTools();
    }
  }

  /** 実行の停滞への対処で用いる取り出し待機中承認ツール一覧を返す。 */
  public dev.mikoto2000.rei.core.chat.RunContinuation.PendingTools takePendingApprovalTools() {
    var pending = pendingApprovalTools;
    pendingApprovalTools = null;
    return pending;
  }

  /** 実行の停滞への対処において、承認継続入力の有無を判定する。 */
  public boolean hasApprovalContinuation() {
    return continuation instanceof dev.mikoto2000.rei.core.policy.ApprovalContinuationService;
  }

  /** 後続の操作を進められるか判断するため、承認継続入力実行を確認する。 */
  public void checkApprovalContinuationExecution() {
    if (continuation
        instanceof dev.mikoto2000.rei.core.policy.ApprovalContinuationService approvals)
      approvals.checkExecution(runContext);
  }

  /** 実行の停滞への対処において、中断対象承認に該当するか判定する。 */
  public boolean suspendForApproval(
      String approvalId,
      List<org.springframework.ai.chat.messages.Message> history,
      dev.mikoto2000.rei.core.chat.RunContinuation.PendingTools pending) {
    if (continuation
        instanceof dev.mikoto2000.rei.core.policy.ApprovalContinuationService approvals) {
      approvals.suspendApproval(this, approvalId, history, pending);
      return true;
    }
    return false;
  }

  /** 実行の停滞への対処で用いる取り出し再開メッセージ一覧を返す。 */
  public List<org.springframework.ai.chat.messages.Message> takeResumeMessages() {
    var saved = resumeMessages;
    resumeMessages = List.of();
    return saved;
  }

  /** 実行の停滞への対処で用いる予算状態の記録を返す。 */
  public OutputLimitRunBudget.Snapshot budgetSnapshot() {
    return budget.snapshot();
  }

  /** 実行の停滞への対処において、親予約の有無を判定する。 */
  public boolean hasParentReservation() {
    return budget.sharedLlmReservation() != null;
  }

  /** 状態表示待機。 */
  private java.util.function.Predicate<List<org.springframework.ai.chat.messages.Message>> dotWait;

  /** 実行の停滞への対処で使用する状態表示待機を設定する。 */
  public void setDotWait(
      java.util.function.Predicate<List<org.springframework.ai.chat.messages.Message>> wait) {
    this.dotWait = wait;
  }

  /** 実行の停滞への対処に関する中断条件付き待機中を処理する。 */
  public void suspendIfWaiting(List<org.springframework.ai.chat.messages.Message> history) {
    checkActive();
    if (dotWait != null && dotWait.test(history))
      throw new dev.mikoto2000.rei.core.chat.RunWaitingException();
    if (continuation != null && continuation.suspend(this, history))
      throw new dev.mikoto2000.rei.core.chat.RunWaitingException();
  }

  /** 照合キー。 */
  public static final String KEY = RunExecutionContext.class.getName();

  /** 権限一覧。 */
  private dev.mikoto2000.rei.core.policy.ToolPermissionGuard permissions;

  /** 実行の停滞への対処で使用するツール権限利用条件の確認を設定する。 */
  public void setToolPermissionGuard(
      dev.mikoto2000.rei.core.policy.ToolPermissionGuard permissions) {
    this.permissions = permissions;
  }

  /** 利用条件に合わない指定を受け付けないよう、ツール引数を検証する。 */
  public void validateToolArguments(String name, String input) {
    if (permissions != null) permissions.validateArguments(name, input, runContext);
    else {
      dev.mikoto2000.rei.websearch.WebSearchArguments.validate(
          name, input, new dev.mikoto2000.rei.websearch.WebSearchProperties().getMaxResults());
      dev.mikoto2000.rei.core.taskstate.TaskStateArguments.validate(name, input);
      dev.mikoto2000.rei.core.policy.ShellFileReadArguments.validate(name, input);
      dev.mikoto2000.rei.core.policy.ProcessWaitArguments.validate(name, input);
      dev.mikoto2000.rei.core.policy.RawToolResultArguments.validate(name, input);
    }
  }

  /** 実行の停滞への対処に関するレポートツール権限失敗情報を処理する。 */
  public void reportToolPermissionFailure(
      String callId, String name, dev.mikoto2000.rei.chatgpt.ChatGptException error) {
    publisher.publishBoundary(
        factory
            .toolFailed(
                callId, name, dev.mikoto2000.rei.chatgpt.ChatGptRunFailure.diagnostic(error))
            .withOwnership(runContext));
  }

  /** 未実行のツール引数について、修正回数の上限内でモデルへ修正理由を返す。 */
  public String argumentCorrection(
      String callId,
      String name,
      dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException error) {
    // 引数の修正を無制限に繰り返さないよう、実行全体で共有する修正回数の予算を守る。
    if (!budget.tryCorrectArguments())
      throw new ExecutionStoppedException(TOOL_ARGUMENT_REPAIR_EXHAUSTED);
    // モデルや画面へ修正理由を返す前に、認証情報が説明へ漏れないようにする。
    String guidance = dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage());
    publisher.publish(
        factory
            .toolFailed(
                callId,
                name,
                new ErrorInformation("InvalidToolArguments", guidance, "invalid_tool_arguments"))
            .withOwnership(runContext));
    return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .writeValueAsString(
            java.util.Map.of(
                "error",
                "INVALID_TOOL_ARGUMENTS",
                "executed",
                false,
                "guidance",
                guidance,
                "instruction",
                "Correct only the invalid call arguments and issue a new tool call. Do not repeat successful sibling calls. This is not permission; normal authorization and approval still apply.",
                "correctionsRemaining",
                budget.remainingArgumentCorrections(),
                "maxCorrections",
                budget.maxArgumentCorrections()));
  }

  /** 後続の操作を進められるか判断するため、ツール権限を確認する。 */
  public void checkToolPermission(String name, String input) {
    if (permissions != null) {
      permissions.check(name, input, runContext);
      return;
    }
    if (runContext != null
        && (runContext.mode() == dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.CONVERSATION
            || runContext.mode() == dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY
                && !dev.mikoto2000.rei.core.policy.ToolPermissionPolicy.intrinsicallyReadOnly(
                    name)))
      throw new dev.mikoto2000.rei.core.policy.ToolPermissionException(
          name, dev.mikoto2000.rei.core.policy.PermissionDecision.DENY);
  }

  /** 実行の識別子。 */
  private final String runId;

  /** 予算。 */
  private final OutputLimitRunBudget budget;

  /** 検出の窓口。 */
  private final StagnationDetector detector = new StagnationDetector();

  /** 評価の判断。 */
  private final ProgressEvaluator evaluator;

  /** 生成元。 */
  private final AgentEventFactory factory;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** plannedツール一覧。 */
  private final List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> plannedTools =
      new ArrayList<>();

  /** 依頼を進める方針を決めるため、ツール一覧の計画を組み立てる。 */
  public void planTools(
      List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls) {
    synchronized (this) {
      plannedTools.clear();
      plannedTools.addAll(calls);
    }
    for (var call : calls) {
      checkActive();
      publisher.publishBoundary(
          new AgentEvent(
                  UUID.randomUUID().toString(),
                  0,
                  java.time.Instant.now(),
                  AgentEventType.TOOL_PLANNED,
                  1,
                  null,
                  null,
                  runId,
                  null,
                  null,
                  new ToolStartedPayload(call.id(), call.name(), "planned", null))
              .withOwnership(runContext));
    }
  }

  /** 対象を処理する担当を決めるため、ツール識別子の利用権を確保する。 */
  public synchronized String claimToolId(String name, String input) {
    for (int i = 0; i < plannedTools.size(); i++)
      if (plannedTools.get(i).name().equals(name) && plannedTools.get(i).arguments().equals(input))
        return plannedTools.remove(i).id();
    return UUID.randomUUID().toString();
  }

  /** ツールの結果一覧再開用の記録。 */
  private java.util.function.Consumer<List<org.springframework.ai.chat.messages.Message>>
      toolResultsCheckpoint = messages -> {};

  private List<org.springframework.ai.chat.messages.Message> lastCheckpointMessages = List.of();

  /** 実行の停滞への対処で使用するツールの結果一覧再開用の記録を設定する。 */
  public synchronized void setToolResultsCheckpoint(
      java.util.function.Consumer<List<org.springframework.ai.chat.messages.Message>> observer) {
    toolResultsCheckpoint = observer;
  }

  /** 実行の停滞への対処に関するpreserveツールの結果一覧を処理する。 */
  public synchronized void preserveToolResults(
      List<org.springframework.ai.chat.messages.Message> messages) {
    // Commit the completed batch's counters before taking the existing atomic checkpoint.
    endIteration();
    lastCheckpointMessages = List.copyOf(messages);
    toolResultsCheckpoint.accept(lastCheckpointMessages);
  }

  /** 実行の停滞への対処で用いる開始永続化されたツールを返す。 */
  public String beginDurableTool(String call, String name, String input) {
    publisher.publishBoundary(
        factory.toolStarted(call, name, "Structured tool execution").withOwnership(runContext));
    return call;
  }

  /** 実行結果を後続の処理へ引き渡すため、永続化されたツールを完了させる。 */
  public void completeDurableTool(String call, String name, String result) {
    String summary = CredentialRedactor.redact(result == null ? "" : result);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (summary.length() > 1000) summary = summary.substring(0, 1000);
    publisher.publishBoundary(
        factory.toolCompleted(call, name, 0, summary).withOwnership(runContext));
  }

  /** 実行の停滞への対処に関するfail永続化されたツールを処理する。 */
  public void failDurableTool(String call, String name, RuntimeException error) {
    publisher.publishBoundary(
        factory
            .toolFailed(
                call, name, dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.safeError(error))
            .withOwnership(runContext));
  }

  /** 待機中。 */
  private final List<ProgressEvidence> pending = new ArrayList<>();

  /** 依存条件一覧。 */
  private final Map<String, dev.mikoto2000.rei.core.dependency.DependencyState> dependencies =
      new LinkedHashMap<>();

  /** ツール呼び出し一覧／待機中観測内容一覧。 */
  private int toolCalls, waitingObservations;

  /** 繰り返し失敗。 */
  private boolean iterationFailed;

  /** 状況を判断する根拠を得るため、依存条件を観測する。 */
  public synchronized void observeDependency(
      dev.mikoto2000.rei.core.dependency.DependencyObservation observation) {
    checkActive();
    var previous = dependencies.put(observation.id(), observation.state());
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (dependencies.size() > 64) dependencies.remove(dependencies.keySet().iterator().next());
    if (observation.state() == dev.mikoto2000.rei.core.dependency.DependencyState.WAITING)
      waitingObservations++;
    else if (previous != observation.state())
      pending.add(
          new ProgressEvidence(
              ProgressEvent.NEW_INFORMATION,
              "Dependency state: " + observation.state(),
              observation.id(),
              observation.state().name()));
  }

  /** 直近操作一覧。 */
  private final Deque<String> recentActions = new ArrayDeque<>();

  /** 直近エラー。 */
  private String lastError = "none";

  /** 直近ツール名。 */
  private String lastToolName = "";

  /** 進捗の更新番号。 */
  private long progressVersion;

  /** 情報の取得限定繰り返し回数。 */
  private int acquisitionOnlyIterations;

  /** 不明繰り返し回数。 */
  private int unknownIterations;

  /** 不明計画の見直し案内の回数。 */
  private int unknownReplanNotices;

  /** 停滞理由。 */
  private String stagnationReason = "no_progress";

  /** 応答生成に使ったトークン数。 */
  private long completionTokens;

  /** 終了済み。 */
  private boolean closed;

  /** キャンセル済み。 */
  private boolean cancelled;

  /** 完了。 */
  private boolean completed;

  /** 繰り返し開始。 */
  private boolean iterationOpen;

  /** 文脈区間。 */
  private long contextSegment;

  /** 実行の停滞への対処で用いる次文脈区間を返す。 */
  public synchronized long nextContextSegment() {
    return ++contextSegment * 1_000_000_000L;
  }

  /** 利用者要求。 */
  private String userRequest = "";

  /** 会話状態の記録。 */
  private List<org.springframework.ai.chat.messages.Message> conversationSnapshot = List.of();

  /** 実行の停滞への対処で使用する会話状態の記録を設定する。 */
  public void setConversationSnapshot(List<org.springframework.ai.chat.messages.Message> messages) {
    conversationSnapshot = List.copyOf(messages);
  }

  /** 実行の停滞への対処で用いる会話状態の記録を返す。 */
  public List<org.springframework.ai.chat.messages.Message> conversationSnapshot() {
    return conversationSnapshot;
  }

  /** 外部委譲使用量。 */
  private boolean externalDelegationUsed;

  /** 外部レビュー結果。 */
  private dev.mikoto2000.rei.externalagent.ExternalAgentResult externalReviewResult;

  /** 実行の停滞への対処で用いる外部レビュー結果を返す。 */
  public synchronized dev.mikoto2000.rei.externalagent.ExternalAgentResult externalReviewResult() {
    return externalReviewResult;
  }

  /** 実行の停滞への対処で使用する外部レビュー結果を設定する。 */
  public synchronized void setExternalReviewResult(
      dev.mikoto2000.rei.externalagent.ExternalAgentResult result) {
    externalReviewResult = result;
  }

  /** 実行の停滞への対処で使用する利用者要求を設定する。 */
  public synchronized void setUserRequest(String request) {
    userRequest = request;
  }

  /** 実行の停滞への対処で用いる利用者要求を返す。 */
  public synchronized String userRequest() {
    return userRequest;
  }

  /** 実行の停滞への対処において、外部委譲使用量に該当するか判定する。 */
  public synchronized boolean externalDelegationUsed() {
    return externalDelegationUsed;
  }

  /** 対象を処理する担当を決めるため、外部委譲の利用権を確保する。 */
  public synchronized boolean claimExternalDelegation() {
    checkActive();
    if (externalDelegationUsed) return false;
    externalDelegationUsed = true;
    return true;
  }

  /** 実行文脈。 */
  private dev.mikoto2000.rei.core.chat.AgentRunContext runContext;

  /** 実行の停滞への対処で使用する実行文脈を設定する。 */
  public void setRunContext(dev.mikoto2000.rei.core.chat.AgentRunContext context) {
    this.runContext = context;
  }

  /** 依頼された操作を進めるため、文脈を実行する。 */
  public dev.mikoto2000.rei.core.chat.AgentRunContext runContext() {
    return runContext;
  }

  /** 追加入力一覧。 */
  private dev.mikoto2000.rei.core.chat.UserInterventionQueue interventions;

  /** 追加入力反映済みの内容。 */
  private java.util.function.Consumer<String> interventionApplied = text -> {};

  /** 実行の停滞への対処で使用する追加入力一覧を設定する。 */
  public void setInterventions(
      dev.mikoto2000.rei.core.chat.UserInterventionQueue queue,
      java.util.function.Consumer<String> applied) {
    this.interventions = queue;
    this.interventionApplied = applied;
  }

  /** 指定された変更を有効にするため、追加入力一覧を反映する。 */
  public synchronized List<org.springframework.ai.chat.messages.Message> applyInterventions() {
    if (cancelled) return List.of();
    if (interventions == null) return List.of();
    return interventions.drainEntries().stream()
        .map(
            entry -> {
              userRequest = entry.text();
              interventionApplied.accept(entry.text());
              publisher.publish(
                  factory
                      .intervention(runId, entry.id(), entry.text(), true)
                      .withOwnership(runContext));
              return (org.springframework.ai.chat.messages.Message)
                  new org.springframework.ai.chat.messages.UserMessage(entry.text());
            })
        .toList();
  }

  /** 指定された値や利用先を使って、RunExecutionContextを初期化する。 */
  public RunExecutionContext(
      String runId,
      OutputLimitRunBudget budget,
      ProgressEvaluator evaluator,
      AgentEventFactory factory,
      AgentEventPublisher publisher) {
    this.runId = runId;
    this.budget = budget;
    this.evaluator = evaluator;
    this.factory = factory;
    this.publisher = publisher;
  }

  /**
   * 実行の停滞への対処で用いる共有言語モデル予約を返す。
   *
   * <p>Children share the local Run counter as well as its optional durable Goal reservation.
   */
  public OutputLimitRunBudget.LlmCallReservation sharedLlmReservation() {
    return new OutputLimitRunBudget.LlmCallReservation() {
      /** 実行の停滞への対処で判断に用いる試行予約の条件を確認する。 */
      public boolean tryReserve() {
        synchronized (RunExecutionContext.this) {
          checkActive();
          return budget.tryConsumeLlmCall();
        }
      }

      /** 実行の停滞への対処で用いる残量を返す。 */
      public int remaining() {
        synchronized (RunExecutionContext.this) {
          return budget.remainingLlmCalls();
        }
      }

      /** トークン数による実行予算の制限が有効かどうかを返す。 */
      public boolean tokenLimitEnabled() {
        return budget.tokenLimitEnabled();
      }

      /** 使用したトークン数を実行予算へ記録する。 */
      public void recordTotalTokens(Integer tokens) {
        RunExecutionContext.this.recordTotalTokens(tokens);
      }
    };
  }

  /** 実行の停滞への対処で用いるモデル呼び出し予算を返す。 */
  public dev.mikoto2000.rei.llm.ModelCallBudget modelCallBudget() {
    return new dev.mikoto2000.rei.llm.ModelCallBudget() {
      /** 実行の停滞への対処の操作を実行する。 */
      public void run() {
        consumeNextLlmCall();
      }

      /** トークン数による実行予算の制限が有効かどうかを返す。 */
      public boolean tokenLimitEnabled() {
        return budget.tokenLimitEnabled();
      }

      /** 使用したトークン数を実行予算へ記録する。 */
      public void recordTotalTokens(Integer tokens) {
        RunExecutionContext.this.recordTotalTokens(tokens);
      }
    };
  }

  /** 使用したトークン数を実行予算へ記録する。 */
  public synchronized void recordTotalTokens(Integer tokens) {
    checkActive();
    budget.recordTotalTokens(tokens);
    if (budget.usageUnknown()) throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
    if (budget.tokenExceeded()) throw new ExecutionStoppedException(TOKEN_BUDGET_EXCEEDED);
  }

  /** 後続の操作を進められるか判断するため、モデルトークン予算を確認する。 */
  public synchronized void checkModelTokenBudget() {
    checkActive();
    if (budget.tokenExhausted())
      throw new ExecutionStoppedException(
          budget.usageUnknown() ? TOKEN_USAGE_UNKNOWN : TOKEN_BUDGET_EXCEEDED);
  }

  /** 実行の停滞への対処で用いる検出の窓口を返す。 */
  public StagnationDetector detector() {
    return detector;
  }

  /** 実行の停滞への対処で用いる評価の判断を返す。 */
  public ProgressEvaluator evaluator() {
    return evaluator;
  }

  /** 実行の停滞への対処で用いる進捗の更新番号を返す。 */
  public long progressVersion() {
    return progressVersion;
  }

  /** 実行の停滞への対処で用いる応答生成に使ったトークン数を返す。 */
  public synchronized long completionTokens() {
    return completionTokens;
  }

  /** 後から経緯や使用量を確認できるよう、応答生成に使ったトークン数を記録する。 */
  public synchronized void recordCompletionTokens(long tokens) {
    completionTokens += Math.max(0, tokens);
  }

  /**
   * 実行結果を後続の処理へ引き渡すため、Subgoalを完了させる。
   *
   * <p>Called only by the executor after a planned subgoal returns SUCCESS, not on arbitrary tool
   * success.
   */
  public synchronized void completeSubgoal(String goal) {
    if (closed || !evaluator.completeSubgoal(goal)) return;
    boolean recovering = detector.replanCount() > 0 || detector.isReplanRequested();
    var evidence =
        new ProgressEvidence(
            ProgressEvent.SUBGOAL_COMPLETED,
            "Planned subgoal completed",
            ProgressEvaluator.actionKey("subgoal", goal));
    detector.recordProgress(evidence.kind());
    acquisitionOnlyIterations = 0;
    goalIdleIterations = 0;
    coverageIterations = 0;
    lastProgress = evidence;
    progressVersion++;
    emit(AgentEventType.PROGRESS_DETECTED, evidence, "subgoal_completed");
    if (recovering) emit(AgentEventType.STAGNATION_RECOVERED, evidence, "progress_resumed");
  }

  /** 実行の停滞への対処で利用した資源や登録を解放し、利用を終了する。 */
  public synchronized void close() {
    closed = true;
    pending.clear();
  }

  /** 実行の停滞への対処に関するexpireを処理する。 */
  public synchronized void expire() {
    close();
    if (interventions != null) interventions.discardAndFinish();
  }

  /** 対象の実行をキャンセルする。 */
  public synchronized void cancel() {
    if (completed) return;
    cancelled = true;
    close();
    int discarded = interventions == null ? 0 : interventions.discardAndFinish();
    if (discarded > 0)
      org.slf4j.LoggerFactory.getLogger(RunExecutionContext.class)
          .info("Cancelled run {}: discarded {} pending guidance", runId, discarded);
  }

  /** 実行の停滞への対処において、キャンセル済みかどうかを判定する。 */
  public synchronized boolean isCancelled() {
    return cancelled;
  }

  /** 実行結果を後続の処理へ引き渡すため、実行を完了させる。 */
  public synchronized void completeRun() {
    checkActive();
    completed = true;
  }

  /** 後続の操作を進められるか判断するため、活動中を確認する。 */
  public synchronized void checkActive() {
    if (closed || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }

  /** 実行の停滞への対処に関する開始繰り返しを処理する。 */
  public synchronized void beginIteration() {
    checkActive();
    latestReadFeedback = "[]";
    pending.clear();
    toolCalls = 0;
    waitingObservations = 0;
    iterationFailed = false;
    iterationOpen = true;
    restorationIteration = false;
  }

  /** 後から経緯や使用量を確認できるよう、ツールを記録する。 */
  public synchronized void recordTool(
      String name, String arguments, String result, ProgressEvaluator.Snapshot before) {
    toolCalls++;
    if (closed) return;
    String action = ProgressEvaluator.actionKey(name, arguments);
    lastToolName = name;
    if (ProgressEvaluator.failedResult(result)) {
      iterationFailed = true;
      lastError = name + ":structured_failure";
      detector.recordFailure("structured_failure", name);
    }
    detector.recordToolCall(name, action);
    recentActions.addLast(action);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (recentActions.size() > 6) recentActions.removeFirst();
    pending.addAll(evaluator.afterTool(name, arguments, result, before));
    String reads = evaluator.readFeedback();
    if (!reads.equals("[]")) latestReadFeedback = reads;
  }

  /** 後から経緯や使用量を確認できるよう、失敗情報を記録する。 */
  public synchronized void recordFailure(String name, String arguments, RuntimeException error) {
    iterationFailed = true;
    if (closed) return;
    evaluator.recordFailure(name, arguments);
    lastError = name + ":" + error.getClass().getSimpleName();
    detector.recordFailure(error.getClass().getSimpleName(), name);
  }

  /** 実行の停滞への対処に関する終了繰り返しを処理する。 */
  public synchronized void endIteration() {
    if (closed || !iterationOpen) return;
    iterationOpen = false;
    // 依存条件の成立を待っているだけの反復を、作業の停滞として数えない。
    if (pending.isEmpty()
        && toolCalls > 0
        && toolCalls == waitingObservations
        && !iterationFailed) {
      emit(AgentEventType.STAGNATION_UPDATED, null, "waiting_for_dependency");
      return;
    }
    boolean recovering = detector.replanCount() > 0 || detector.isReplanRequested();
    boolean restored = pending.stream().anyMatch(e -> e.kind() == ProgressEvent.CONTEXT_RESTORED);
    boolean onlyRestoration =
        restored
            && !iterationFailed
            && pending.stream().allMatch(e -> e.kind() == ProgressEvent.CONTEXT_RESTORED);
    if (onlyRestoration) {
      restorationIteration = true;
      for (var evidence : pending)
        emit(AgentEventType.PROGRESS_DETECTED, evidence, "context_restored");
      pending.clear();
      return;
    }
    boolean restorationFailed =
        evaluator.restoringContext() || "CONTEXT_RESTORATION_FAILED".equals(stagnationReason);
    boolean work =
        pending.stream()
            .anyMatch(
                e ->
                    e.kind() == ProgressEvent.SUBGOAL_COMPLETED
                        || e.kind() == ProgressEvent.STATE_CHANGED
                        || e.kind() == ProgressEvent.STEP_CHANGED);
    // Preparatory observations do not prove restoration finished. Keep the bounded targets
    // available until work advances; ordinary no-progress iterations still count below.
    if (work) evaluator.finishContextRestoration();
    boolean acquisitionProgress = pending.stream().anyMatch(ProgressEvidence::acquisitionProgress);
    boolean known =
        pending.stream()
            .anyMatch(
                e ->
                    e.kind() != ProgressEvent.INFORMATION_UNKNOWN
                        && e.kind() != ProgressEvent.CONTEXT_RESTORED);
    boolean unknown = pending.stream().anyMatch(e -> e.kind() == ProgressEvent.INFORMATION_UNKNOWN);
    // 作業状態の変化と情報収集の進展を区別し、読み取りだけで成果が完成したと見なさない。
    if (work) acquisitionOnlyIterations = 0;
    // 同じファイルでも未確認の範囲を読むことは情報収集の進展として認める。
    // ただし、作業の完了や共有予算の回復としては扱わない。
    else if (known && !acquisitionProgress) acquisitionOnlyIterations++;
    if (work) {
      goalIdleIterations = 0;
      coverageIterations = 0;
    } else {
      goalIdleIterations = Math.min(GOAL_IDLE_LIMIT, goalIdleIterations + 1);
      if (acquisitionProgress)
        coverageIterations = Math.min(GOAL_IDLE_LIMIT, coverageIterations + 1);
    }
    if (unknown && !known) unknownIterations++;
    else unknownIterations = 0;
    boolean stalledWork =
        acquisitionOnlyIterations >= ACQUISITION_ONLY_LIMIT && !acquisitionProgress;
    stagnationReason =
        goalIdleIterations >= GOAL_IDLE_LIMIT && coverageIterations > 0
            ? "COVERAGE_WITHOUT_WORK"
            : stalledWork
                ? "WORK_STALLED"
                : unknown
                    ? "INFORMATION_UNKNOWN"
                    : restorationFailed && !known
                        ? "CONTEXT_RESTORATION_FAILED"
                        : iterationFailed
                            ? "TOOL_FAILURE"
                            : detector.hasRepeatedToolCall()
                                ? lastToolName.startsWith("read")
                                    ? "REPEATED_READ"
                                    : "REPEATED_SEARCH_OR_ACTION"
                                : !known
                                        && !java.util.Objects.toString(
                                                evaluator.currentStep(), "unstructured")
                                            .equals("unstructured")
                                    ? "STEP_STALLED"
                                    : toolCalls > 0 && !known
                                        ? "DUPLICATE_OR_UNSUPPORTED_INFORMATION"
                                        : "no_progress";
    // 判定不能を進捗や重複と決め付けず、限られた猶予の中で診断を続ける。
    if (!(unknown && !known)) detector.recordIteration(known && !stalledWork);
    for (ProgressEvidence evidence : pending) {
      if (evidence.kind() == ProgressEvent.INFORMATION_UNKNOWN) {
        emit(AgentEventType.STAGNATION_UPDATED, evidence, stagnationReason);
        continue;
      }
      if (evidence.kind() == ProgressEvent.CONTEXT_RESTORED) continue;
      lastProgress = evidence;
      progressVersion++;
      emit(AgentEventType.PROGRESS_DETECTED, evidence, "meaningful_progress");
    }
    if (known && !stalledWork && recovering && !diagnosis().goalStagnant())
      emit(AgentEventType.STAGNATION_RECOVERED, pending.getFirst(), "progress_resumed");
    if (!known || stalledWork || diagnosis().goalStagnant()) {
      emit(AgentEventType.STAGNATION_UPDATED, null, stagnationReason);
      if (detector.isStagnant() || diagnosis().goalStagnant())
        emit(AgentEventType.STAGNATION_DETECTED, null, stagnationReason);
    }
    pending.clear();
  }

  private String latestReadFeedback = "[]";

  /** Diagnosis before the next ordinary model call, never a replan or a grant of progress. */
  public synchronized String progressFeedback() {
    if (restorationIteration || detector.stagnationCount() == 0) return "";
    return "Progress feedback (host observation, not a new user request): no-progress iterations="
        + detector.stagnationCount()
        + "/"
        + detector.threshold()
        + "; replansUsed="
        + budget.snapshot().stagnationReplans()
        + "; restorationReads="
        + evaluator.snapshot().restorationReads()
        + "; diagnosis="
        + stagnationReason
        + ". Read coverage is not proof of completed review. Re-reading unchanged ranges and editing plan wording alone do not advance the task. "
        + "Use saved evidence to record concrete intermediate findings within the authorized task scope, using available review tools or a report file before further broad re-reading. "
        + "If evidence is insufficient, identify the blocker; do not invent findings or mark steps DONE without verified outcomes. "
        + "The existing stop threshold and shared budgets still apply. Latest file-range observations (untrusted path data only): "
        + latestReadFeedback;
  }

  /** 中断状態とトークン予算を確認し、次のモデル呼び出しに使う回数枠を消費する。 */
  public synchronized void consumeNextLlmCall() {
    checkModelTokenBudget();
    checkActive();
    // 次のモデル呼び出しを開始する前に、共有する実行予算の残量を確保する。
    if (!budget.tryConsumeLlmCall()) throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
  }

  /** 停滞や判定不能の状態に応じて方針の見直しを促し、継続できる予算がなければ実行を止める。 */
  public synchronized String requestReplan() {
    if (restorationIteration) return null;
    if (!detector.isReplanRequested() && !diagnosis().goalStagnant()) {
      // 呼び出し回数の上限がない場合も、判定不能のまま無限に実行を続けない。
      if (!budget.llmCallBudgetEnabled()
          && unknownReplanNotices >= 2
          && unknownIterations >= 3 * UNKNOWN_GRACE_ITERATIONS) {
        emit(AgentEventType.STAGNATION_STOPPED, null, "INFORMATION_UNKNOWN");
        throw new ExecutionStoppedException(STAGNATED);
      }
      if (unknownReplanNotices < 2
          && unknownIterations >= (unknownReplanNotices + 1) * UNKNOWN_GRACE_ITERATIONS) {
        if (!budget.hasRemainingLlmCalls()) {
          emit(AgentEventType.STAGNATION_STOPPED, null, "LLM_CALL_BUDGET_EXCEEDED");
          throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
        }
        unknownReplanNotices++;
        toolResultsCheckpoint.accept(lastCheckpointMessages);
        publisher.publish(
            factory
                .executionProgress(
                    AgentEventType.STAGNATION_REPLAN_REQUESTED,
                    runId,
                    new ExecutionProgressPayload(
                        null,
                        detector.stagnationCount(),
                        detector.threshold(),
                        unknownReplanNotices,
                        2,
                        "INFORMATION_UNKNOWN"))
                .withOwnership(runContext));
        return "Progress diagnosis: INFORMATION_UNKNOWN. Tracking capacity or historical evidence is insufficient to classify these reads. Replan toward independently verifiable work outcomes. Do not infer completion from unknown information."
            + (budget.llmCallBudgetEnabled()
                ? " The remaining shared Run budget is finite."
                : " Call limiting is disabled; continued inconclusive tracking after diagnosis will stop the Run.");
      }
      return null;
    }
    if (detector.isMaxReplanReached()) {
      throw stagnationStop(STAGNATED);
    }
    if (!budget.hasRemainingLlmCalls()) {
      emit(AgentEventType.STAGNATION_STOPPED, null, "LLM_CALL_BUDGET_EXCEEDED");
      throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
    }
    if (!budget.tryConsumeStagnationReplan()) {
      throw stagnationStop(REPLAN_BUDGET_EXCEEDED);
    }
    String notice =
        new StagnationAdvisor(detector).replanNotice(String.join(", ", recentActions), lastError);
    notice +=
        "\nProgress diagnosis: "
            + stagnationReason
            + ". Information acquisition does not complete planned work. Confirm work outcomes; do not infer completion from reads.";
    String recovery =
        recoveries.isEmpty()
            ? recoveryGuidance()
            : "既に試した復旧では解消していません。工程の完了条件と阻害要因を確認し、既存Planning Loopで実行方法を変更してください。 "
                + recoveryGuidance();
    if (recoveries.size() < 2)
      recoveries.add(stagnationReason + ":attempt-" + (recoveries.size() + 1));
    notice += "\n" + recovery;
    detector.recordReplan();
    acquisitionOnlyIterations = 0;
    goalIdleIterations = 0;
    coverageIterations = 0;
    unknownIterations = 0;
    emit(AgentEventType.STAGNATION_REPLAN_REQUESTED, null, stagnationReason);
    toolResultsCheckpoint.accept(lastCheckpointMessages);
    return notice;
  }

  /** 実行の停滞への対処に関する通知を処理する。 */
  private void emit(AgentEventType type, ProgressEvidence evidence, String reason) {
    publisher.publish(
        factory
            .executionProgress(
                type,
                runId,
                new ExecutionProgressPayload(
                    evidence,
                    detector.stagnationCount(),
                    detector.threshold(),
                    detector.replanCount(),
                    detector.maxReplans(),
                    reason,
                    diagnosisDetails()))
            .withOwnership(runContext));
  }

  /** Keep the event boundary independent of Run implementation types. */
  private Map<String, Object> diagnosisDetails() {
    var current = diagnosis();
    return Map.ofEntries(
        Map.entry("localStagnant", current.localStagnant()),
        Map.entry("goalStagnant", current.goalStagnant()),
        Map.entry("goalIdleIterations", current.goalIdleIterations()),
        Map.entry("goalThreshold", current.goalThreshold()),
        Map.entry("coverageIterations", current.coverageIterations()),
        Map.entry("currentStep", current.currentStep()),
        Map.entry("lastProgress", lastProgress == null ? Map.of() : lastProgress),
        Map.entry("reason", current.reason()),
        Map.entry("recentActions", current.recentActions()),
        Map.entry("recoveries", current.recoveries()),
        Map.entry("replanBudget", current.replanBudget()),
        Map.entry("remainingSteps", current.remainingSteps()));
  }
}
