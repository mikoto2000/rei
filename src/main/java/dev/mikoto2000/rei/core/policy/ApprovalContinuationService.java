package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 承認継続入力のユースケースを実現する。
 *
 * <p>One human decision queues a saved invocation; workers and model calls are released while
 * waiting.
 */
@Component
@Primary
public class ApprovalContinuationService implements RunContinuation {
  /** 承認一覧。 */
  private final ToolApprovalRepository approvals;

  /** 保存先。 */
  private final ApprovalContinuationStore store;

  /** 登録台帳。 */
  private final ObjectProvider<RunRegistry> registry;

  /** 実行一覧。 */
  private final ObjectProvider<RunService> runs;

  /** 入力の振り分け。 */
  private final ObjectProvider<ConversationInputRouter> router;

  /** チャット。 */
  private final ObjectProvider<ChatExecutionService> chat;

  /** Slackとの連携に用いる窓口。 */
  private final ObjectProvider<dev.mikoto2000.rei.slack.SlackAgentService> slack;

  /** プロジェクト一覧。 */
  private final ObjectProvider<dev.mikoto2000.rei.core.project.ProjectRegistry> projects;

  /** 会話一覧。 */
  private final ObjectProvider<dev.mikoto2000.rei.application.session.SessionRepository> sessions;

  /** 一連の更新。 */
  private final TransactionTemplate transaction;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 管理対象。 */
  private final Map<String, Managed> managed = new ConcurrentHashMap<>();

  /** 再開用の記録一覧。 */
  private dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints;

  /** ツール利用の権限制御で使用する再開用の記録一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCheckpoints(
      dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints) {
    this.checkpoints = checkpoints;
  }

  /**
   * ツール利用の権限制御で受け渡す管理対象を表す。
   *
   * @param reservation 予約
   * @param completed 完了
   */
  private record Managed(
      OutputLimitRunBudget.LlmCallReservation reservation,
      Consumer<ChatExecutionResult> completed) {}

  /** 指定された値や利用先を使って、ApprovalContinuationServiceを初期化する。 */
  public ApprovalContinuationService(
      ToolApprovalRepository approvals,
      ApprovalContinuationStore store,
      ObjectProvider<RunRegistry> registry,
      ObjectProvider<RunService> runs,
      ObjectProvider<ConversationInputRouter> router,
      ObjectProvider<ChatExecutionService> chat,
      ObjectProvider<dev.mikoto2000.rei.slack.SlackAgentService> slack,
      ObjectProvider<dev.mikoto2000.rei.core.project.ProjectRegistry> projects,
      ObjectProvider<dev.mikoto2000.rei.application.session.SessionRepository> sessions,
      @Qualifier("memoryConsolidationDataSource") DataSource source,
      Clock clock) {
    this.approvals = approvals;
    this.store = store;
    this.registry = registry;
    this.runs = runs;
    this.router = router;
    this.chat = chat;
    this.slack = slack;
    this.projects = projects;
    this.sessions = sessions;
    this.clock = clock;
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  /** ツール利用の権限制御で用いる状態を返す。 */
  public Optional<String> state(String id) {
    return store.find(id).map(ApprovalContinuationStore.Entry::state);
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<ToolApprovalRepository.Request> list(String project) {
    var rows = new LinkedHashMap<String, ToolApprovalRepository.Request>();
    approvals.list(project).forEach(request -> rows.put(request.id(), request));
    store
        .visible(project)
        .forEach(
            id -> {
              var request = approvals.get(project, id);
              rows.put(id, request);
            });
    return rows.values().stream().limit(256).toList();
  }

  /** ツール利用の権限制御において、待機中承認に該当するか判定する。 */
  public boolean waitingApproval(AgentRunContext owner) {
    return store.active().stream()
        .anyMatch(
            e ->
                e.runId().equals(owner.runId())
                    && Set.of("WAITING_APPROVAL", "QUEUED", "DENY_QUEUED").contains(e.state()));
  }

  /** ツール利用の権限制御に関する中断承認を処理する。 */
  public void suspendApproval(
      RunExecutionContext execution, String id, List<Message> history, PendingTools pending) {
    var owner = execution.runContext();
    execution.checkActive();
    validate(owner);
    var request = approvals.get(owner.projectId(), id);
    if (!request.runId().equals(owner.runId())
        || !request.sessionId().equals(owner.conversationId())
        || !Set.of("PENDING", "APPROVED").contains(request.status()))
      throw new IllegalStateException("Approval ownership/state changed");
    var current = registry.getObject().get(owner.runId());
    if (!current.context().equals(owner)
        || current.status() != RunStatus.RUNNING
        || !registry.getObject().ownsExecution(owner.runId()))
      throw new IllegalStateException("Approval lost Run ownership");
    if (execution.hasParentReservation() && !managed.containsKey(owner.runId()))
      throw new IllegalStateException("Managed parent required to preserve shared budget");
    execution.endIteration();
    var partialHistory = new ArrayList<>(history);
    partialHistory.add(
        org.springframework.ai.chat.messages.AssistantMessage.builder()
            .content(pending.text())
            .toolCalls(pending.calls())
            .build());
    partialHistory.add(
        org.springframework.ai.chat.messages.ToolResponseMessage.builder()
            .responses(pending.completed())
            .build());
    execution.preserveToolResults(partialHistory);
    var previous = store.resuming(owner.runId());
    store.save(
        id,
        owner,
        execution.userRequest(),
        history,
        pending,
        execution.budgetSnapshot(),
        managed.containsKey(owner.runId()) || execution.hasParentReservation());
    previous.ifPresent(
        old -> {
          approvals.retireContinuationGrant(owner.projectId(), old.approvalId());
          store.state(old.approvalId(), "COMPLETED");
        });
    if (!registry.getObject().transition(owner.runId(), RunStatus.WAITING_APPROVAL, null)) {
      store.state(id, "CANCELLED");
      throw new java.util.concurrent.CancellationException();
    }
  }

  /**
   * ツール利用の権限制御で用いる判断を返す。
   *
   * <p>Approval and continuation acceptance commit together, with an idempotent durable receipt.
   */
  public ToolApprovalRepository.Request decide(
      String project, String id, boolean approved, boolean continueRun) {
    if (continueRun && !approved)
      throw new IllegalArgumentException("Only approval can continue a Run");
    return transaction.execute(
        tx -> {
          var current = approvals.get(project, id);
          var entry = store.find(id);
          if (continueRun) {
            var saved =
                entry.orElseThrow(
                    () ->
                        new IllegalStateException(
                            "No saved invocation; approve normally and use /resume list for this older request"));
            var owner = store.document(saved).owner();
            if (!project.equals(owner.projectId())
                || !current.runId().equals(owner.runId())
                || !current.sessionId().equals(owner.conversationId()))
              throw new IllegalArgumentException("Approval continuation ownership mismatch");
            if (Set.of("QUEUED", "RESUMING", "COMPLETED").contains(saved.state())) return current;
            if (!saved.state().equals("WAITING_APPROVAL"))
              throw new IllegalStateException(
                  "Continuation is " + saved.state() + "; inspect before retrying");
            validate(owner);
            if (!clock.instant().isBefore(current.expiresAt()))
              throw new IllegalStateException(
                  "Approval expired; saved invocation was not executed");
            if (registry.getObject().get(owner.runId()).status() != RunStatus.WAITING_APPROVAL)
              throw new IllegalStateException("Run no longer waiting for approval");
          }
          var decided =
              current.status().equals("PENDING")
                  ? approvals.decide(project, id, approved)
                  : current;
          if (!decided.status().equals(approved ? "APPROVED" : "DENIED")
              && !(continueRun && decided.status().equals("CONSUMED")))
            throw new IllegalStateException("Approval already decided differently");
          if (continueRun && !store.queue(id))
            throw new IllegalStateException("Continuation acceptance conflict");
          if (!approved) entry.ifPresent(saved -> store.deny(id));
          return decided;
        });
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
  @Override
  public Saved restore(AgentRunContext owner) {
    var entry = store.resuming(owner.runId());
    if (entry.isPresent()) {
      if (!store.document(entry.get()).owner().equals(owner))
        throw new IllegalArgumentException("Continuation ownership mismatch");
      return store.saved(entry.get());
    }
    var external = slack.getIfAvailable();
    return external == null ? null : external.restore(owner);
  }

  /** ツール利用の権限制御において、中断に該当するか判定する。 */
  @Override
  public boolean suspend(RunExecutionContext execution, List<Message> history) {
    var external = slack.getIfAvailable();
    boolean waiting = external != null && external.suspend(execution, history);
    if (waiting)
      store
          .resuming(execution.runContext().runId())
          .ifPresent(
              entry -> {
                approvals.retireContinuationGrant(
                    execution.runContext().projectId(), entry.approvalId());
                store.state(entry.approvalId(), "COMPLETED");
              });
    return waiting;
  }

  /** ツール利用の権限制御に関する終端を処理する。 */
  @Override
  public void terminal(AgentRunContext owner) {
    if (store.active().stream().anyMatch(entry -> entry.runId().equals(owner.runId()))) {
      var status = registry.getObject().get(owner.runId()).status();
      store.active().stream()
          .filter(entry -> entry.runId().equals(owner.runId()))
          .forEach(
              entry -> approvals.retireContinuationGrant(owner.projectId(), entry.approvalId()));
      store.finishRun(
          owner.runId(),
          status == RunStatus.COMPLETED
              ? "COMPLETED"
              : status == RunStatus.CANCELLED ? "CANCELLED" : "UNKNOWN");
    }
    var external = slack.getIfAvailable();
    if (external != null) external.terminal(owner);
  }

  /** 要求を後続の処理へ引き渡すため、案内を受け付ける。 */
  @Override
  public void acceptGuidance(AgentRunContext owner, List<Message> guidance) {
    if (store.active().stream().anyMatch(e -> e.runId().equals(owner.runId())))
      store.guidance(owner, guidance);
    else {
      var external = slack.getIfAvailable();
      if (external != null) external.acceptGuidance(owner, guidance);
    }
  }

  /** ツール利用の権限制御に関する関連付け親を処理する。 */
  @Override
  public void attachParent(
      AgentRunContext owner,
      OutputLimitRunBudget.LlmCallReservation reservation,
      Consumer<ChatExecutionResult> completed) {
    managed.put(owner.runId(), new Managed(reservation, completed));
    var external = slack.getIfAvailable();
    if (external != null)
      external.attachParent(
          owner,
          reservation,
          result -> {
            managed.remove(owner.runId());
            completed.accept(result);
          });
  }

  /** ツール利用の権限制御に関する関連付けの解除親を処理する。 */
  @Override
  public void detachParent(AgentRunContext owner) {
    managed.remove(owner.runId());
    var external = slack.getIfAvailable();
    if (external != null) external.detachParent(owner);
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private void validate(AgentRunContext owner) {
    if (owner == null || owner.projectId() == null)
      throw new IllegalArgumentException("Owning Project required");
    var project =
        projects
            .getObject()
            .resolveById(owner.projectId())
            .orElseThrow(() -> new IllegalStateException("Project no longer exists"));
    if (!project.root().equals(owner.projectRoot())
        || sessions
            .getObject()
            .findById(owner.conversationId())
            .filter(s -> s.projectId().equals(owner.projectId()))
            .isEmpty()) throw new IllegalStateException("Project or Session ownership changed");
  }

  /** 後続の操作を進められるか判断するため、実行を確認する。 */
  public void checkExecution(AgentRunContext owner) {
    validate(owner);
    var current = registry.getObject().get(owner.runId());
    if (!current.context().equals(owner)
        || current.status() != RunStatus.RUNNING
        || !registry.getObject().ownsExecution(owner.runId()))
      throw new java.util.concurrent.CancellationException(
          "Continuation Run no longer owns execution");
  }

  /** ツール利用の権限制御に関するpumpを処理する。 */
  @Scheduled(fixedDelay = 500, scheduler = "approvalContinuationScheduler")
  public synchronized void pump() {
    for (var entry : store.active()) {
      try {
        var doc = store.document(entry);
        var owner = doc.owner();
        var current = registry.getObject().get(owner.runId());
        if (current.status() == RunStatus.CANCELLED) {
          approvals.retireContinuationGrant(owner.projectId(), entry.approvalId());
          store.state(entry.approvalId(), "CANCELLED");
          notifyParent(owner, ChatExecutionResult.cancelled());
          continue;
        }
        if (entry.state().equals("RESUMING")) {
          if (!store.ownerAlive(entry)) {
            approvals.retireContinuationGrant(owner.projectId(), entry.approvalId());
            store.state(entry.approvalId(), "UNKNOWN");
            if (registry.getObject().claimWaiting(owner))
              runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
            markCheckpoint(owner, "UNKNOWN");
          }
          continue;
        }
        if (router.getObject().containsRun(owner.projectId(), owner.runId())) continue;
        var request = approvals.get(owner.projectId(), entry.approvalId());
        if (!clock.instant().isBefore(request.expiresAt())) {
          store.state(entry.approvalId(), "EXPIRED");
          stop(owner, ChatExecutionResult.failed("Approval expired", "approval_expired"));
          continue;
        }
        if (request.status().equals("DENIED") || entry.state().equals("DENY_QUEUED")) {
          store.state(entry.approvalId(), "DENIED");
          stop(owner, ChatExecutionResult.cancelled());
          continue;
        }
        if (!entry.state().equals("QUEUED")
            || current.status() != RunStatus.WAITING_APPROVAL
            || router.getObject().containsRun(owner.projectId(), owner.runId())) continue;
        try {
          validate(owner);
        } catch (RuntimeException changed) {
          approvals.retireContinuationGrant(owner.projectId(), entry.approvalId());
          store.state(entry.approvalId(), "UNKNOWN");
          if (registry.getObject().claimWaiting(owner))
            runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
          markCheckpoint(owner, "UNKNOWN");
          continue;
        }
        if (doc.managedParent() && !managed.containsKey(owner.runId())) {
          approvals.retireContinuationGrant(owner.projectId(), entry.approvalId());
          store.state(entry.approvalId(), "UNKNOWN");
          if (registry.getObject().claimWaiting(owner))
            runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
          markCheckpoint(owner, "UNKNOWN");
          continue;
        }
        if (!registry.getObject().claimWaiting(owner) || !store.claim(entry.approvalId())) continue;
        router
            .getObject()
            .submitContinuation(
                owner,
                doc.request(),
                queue -> {
                  ChatExecutionResult result;
                  try {
                    validate(owner);
                    if (!clock
                        .instant()
                        .isBefore(
                            approvals.get(owner.projectId(), entry.approvalId()).expiresAt())) {
                      store.state(entry.approvalId(), "EXPIRED");
                      runs.getObject().finishMissingTerminal(owner, RunStatus.FAILED);
                      markCheckpoint(owner, "FAILED");
                      notifyParent(
                          owner,
                          ChatExecutionResult.failed(
                              "Approval expired before queued execution", "approval_expired"));
                      return;
                    }
                    var parent = managed.get(owner.runId());
                    result =
                        chat.getObject()
                            .execute(
                                owner,
                                doc.request(),
                                queue,
                                parent == null ? null : parent.reservation());
                  } catch (RuntimeException uncertain) {
                    approvals.retireContinuationGrant(owner.projectId(), entry.approvalId());
                    store.state(entry.approvalId(), "UNKNOWN");
                    runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
                    notifyParent(
                        owner,
                        ChatExecutionResult.failed(
                            "Approval continuation outcome unknown", "approval_outcome_unknown"));
                    return;
                  }
                  if (result.status() != ChatExecutionResult.Status.WAITING) {
                    approvals.retireContinuationGrant(owner.projectId(), entry.approvalId());
                    store.state(
                        entry.approvalId(),
                        result.status() == ChatExecutionResult.Status.CANCELLED
                            ? "CANCELLED"
                            : result.success() ? "COMPLETED" : "UNKNOWN");
                    runs.getObject()
                        .finishMissingTerminal(
                            owner,
                            result.status() == ChatExecutionResult.Status.CANCELLED
                                ? RunStatus.CANCELLED
                                : result.success() ? RunStatus.COMPLETED : RunStatus.FAILED);
                    notifyParent(owner, result);
                  }
                },
                work -> runs.getObject().execute(owner, work));
      } catch (RuntimeException unavailable) {
        // A claimed submission is uncertain; never silently requeue the side effect.
        if (store.find(entry.approvalId()).map(e -> e.state().equals("RESUMING")).orElse(false)) {
          approvals.retireContinuationGrant(
              store.document(entry).owner().projectId(), entry.approvalId());
          store.state(entry.approvalId(), "UNKNOWN");
          try {
            var owner = store.document(entry).owner();
            if (registry.getObject().claimWaiting(owner))
              runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
            markCheckpoint(owner, "UNKNOWN");
          } catch (RuntimeException unavailableOwner) {
            /* Receipt still records UNKNOWN for explicit inspection. */
          }
        }
      }
    }
  }

  /** 実行中の処理を停止する。 */
  private void stop(AgentRunContext owner, ChatExecutionResult result) {
    if (registry.getObject().claimWaiting(owner)) {
      var status =
          result.status() == ChatExecutionResult.Status.CANCELLED
              ? RunStatus.CANCELLED
              : RunStatus.FAILED;
      runs.getObject().finishMissingTerminal(owner, status);
      markCheckpoint(owner, status.name());
      notifyParent(owner, result);
    }
  }

  /** 後続の判断に現在の状態を反映するため、再開用の記録の状態を記録する。 */
  private void markCheckpoint(AgentRunContext owner, String status) {
    if (checkpoints == null) return;
    try {
      checkpoints
          .findByRun(owner.projectId(), owner.runId())
          .filter(
              saved ->
                  saved.runId().equals(owner.runId())
                      && saved.sessionId().equals(owner.conversationId())
                      && !checkpoints.leased(saved.projectId(), saved.taskId()))
          .ifPresent(
              saved -> {
                var fields = checkpoints.fields(saved);
                fields.put("status", status);
                fields.put("interruptionReason", "approval_" + status.toLowerCase(Locale.ROOT));
                checkpoints.save(
                    checkpoints.fields(fields),
                    saved.revision(),
                    "approval-stop:" + owner.runId() + ":" + status);
              });
    } catch (RuntimeException conflict) {
      /* Do not overwrite an active or changed checkpoint. */
    }
  }

  /** 状態の変化を利用先へ伝えるため、親を通知する。 */
  private void notifyParent(AgentRunContext owner, ChatExecutionResult result) {
    var parent = managed.remove(owner.runId());
    var external = slack.getIfAvailable();
    if (external != null) external.detachParent(owner);
    if (parent != null) parent.completed().accept(result);
  }
}
