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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
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
  private final Function<String, List<ToolApprovalRepository.Request>> listApprovals;

  private final BiFunction<String, String, ToolApprovalRepository.Request> findApproval;
  private final Function<ApprovalDecision, ToolApprovalRepository.Request> decideApproval;
  private final BiConsumer<String, String> retireContinuationGrant;

  /** 保存先。 */
  private final java.util.function.Supplier<ApprovalContinuationStore> store;

  private final InferenceExportGuard inferenceGuard;

  /** Use the existing administrator policy while keeping inference and tool grants independent. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureInferencePolicy(ToolPermissionPolicy policy) {
    inferenceGuard.configurePolicy(policy);
  }

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
  private BiConsumer<AgentRunContext, String> checkpointTransition = (owner, status) -> {};

  /** ツール利用の権限制御で使用する再開用の記録一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCheckpoints(
      dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints) {
    this.checkpointTransition =
        (owner, status) -> {
          if (checkpoints != null) updateUnleasedCheckpoint(checkpoints, owner, status);
        };
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
    this.listApprovals = project -> approvals.list(project);
    this.findApproval = (project, id) -> approvals.get(project, id);
    this.decideApproval =
        decision -> approvals.decide(decision.project(), decision.id(), decision.approved());
    this.retireContinuationGrant = (project, id) -> approvals.retireContinuationGrant(project, id);
    this.store = () -> store;
    this.inferenceGuard = new InferenceExportGuard(approvals, clock);
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
    return store.get().find(id).map(ApprovalContinuationStore.Entry::state);
  }

  /** Human-only read view; neither request options nor executable callbacks are exported. */
  public record InferenceView(
      String approvalId,
      Map<String, String> destination,
      String connectionFingerprint,
      String contentFingerprint,
      List<Map<String, Object>> messages,
      List<ApprovalContinuationStore.InferenceTool> tools) {
    public InferenceView {
      destination = Map.copyOf(destination);
      messages = messages.stream().map(message -> Map.copyOf(message)).toList();
      tools = List.copyOf(tools);
    }

    @Override
    public List<Map<String, Object>> messages() {
      return List.copyOf(messages);
    }

    @Override
    public String toString() {
      return "InferenceView[approvalId="
          + approvalId
          + ", contentFingerprint="
          + contentFingerprint
          + "]";
    }
  }

  /** Resolve only the owning project's verified, protected pre-inference snapshot. */
  public Optional<InferenceView> inference(String project, String id) {
    var request = findApproval.apply(project, id);
    var found = store.get().find(id);
    if (found.isEmpty()) return Optional.empty();
    var entry = found.get();
    var document = store.get().document(entry);
    if (document.inference() == null) return Optional.empty();
    var owner = document.owner();
    if (!project.equals(owner.projectId())
        || !request.runId().equals(owner.runId())
        || !request.sessionId().equals(owner.conversationId()))
      throw new IllegalArgumentException("Inference inspection ownership mismatch");
    validate(owner);
    dev.mikoto2000.rei.modeloptions.AcceptedRunOptions connection = document.connection();
    inferenceGuard.verifySavedApproval(id, owner, connection, document.inference());
    var messages = store.get().inferenceMessages(entry);
    return Optional.of(
        new InferenceView(
            id,
            connection.inferenceDescription(),
            connection.fingerprint(),
            document.inference().contentFingerprint(),
            messages,
            document.inference().tools()));
  }

  /** Portable human inspection data, independent of either storage or Web JSON mapper types. */
  public Optional<Map<String, Object>> inferenceData(String project, String id) {
    return inference(project, id)
        .map(view ->
            Map.<String, Object>of(
                "approvalId",
                view.approvalId(),
                "destination",
                view.destination(),
                "connectionFingerprint",
                view.connectionFingerprint(),
                "contentFingerprint",
                view.contentFingerprint(),
                "messages",
                view.messages(),
                "tools",
                view.tools().stream()
                    .map(tool ->
                        Map.<String, Object>of(
                            "name",
                            tool.name(),
                            "description",
                            tool.description(),
                            "schema",
                            tool.schema(),
                            "returnDirect",
                            tool.returnDirect(),
                            "requiresConfirmation",
                            tool.requiresConfirmation()))
                    .toList()));
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<ToolApprovalRepository.Request> list(String project) {
    var rows = new LinkedHashMap<String, ToolApprovalRepository.Request>();
    listApprovals.apply(project).forEach(request -> rows.put(request.id(), request));
    store
        .get()
        .visible(project)
        .forEach(id -> {
          var request = findApproval.apply(project, id);
          rows.put(id, request);
        });
    return rows.values().stream().limit(256).toList();
  }

  /** ツール利用の権限制御において、待機中承認に該当するか判定する。 */
  public boolean waitingApproval(AgentRunContext owner) {
    return store.get().active().stream()
        .anyMatch(e ->
            e.runId().equals(owner.runId())
                && Set.of("WAITING_APPROVAL", "QUEUED", "DENY_QUEUED").contains(e.state()));
  }

  /** Persist the exact pre-inference input and release the worker without a fake tool call. */
  @Override
  public Optional<org.springframework.ai.chat.prompt.Prompt> prepareInference(
      RunExecutionContext execution, org.springframework.ai.chat.prompt.Prompt prompt) {
    var owner = execution.runContext();
    execution.checkActive();
    checkExecution(owner);
    if (execution.hasParentReservation() && !managed.containsKey(owner.runId()))
      throw new IllegalStateException("Managed parent required to preserve shared budget");
    var connection = execution.progressSnapshot().connection();
    var decision = inferenceGuard.authorize(owner, connection, prompt);
    if (decision.permitted()) return Optional.of(decision.prompt());
    var prepared = InferenceExportRequest.prepare(decision.prompt());
    var input = prepared.input();
    String id = decision.approvalId();
    inferenceGuard.verifySavedApproval(id, owner, connection, input);
    execution.captureModelInput(prepared.prompt());
    execution.endIteration();
    var previous = store.get().resuming(owner.runId());
    store
        .get()
        .saveInference(
            id,
            owner,
            execution.userRequest(),
            execution.budgetSnapshot(),
            managed.containsKey(owner.runId()) || execution.hasParentReservation(),
            execution.protocolHistorySnapshot(),
            connection,
            input);
    previous.ifPresent(old -> {
      retireContinuationGrant.accept(owner.projectId(), old.approvalId());
      store.get().state(old.approvalId(), "COMPLETED");
    });
    if (!registry.getObject().transition(owner.runId(), RunStatus.WAITING_APPROVAL, null)) {
      store.get().state(id, "CANCELLED");
      throw new java.util.concurrent.CancellationException();
    }
    return Optional.empty();
  }

  /** ツール利用の権限制御に関する中断承認を処理する。 */
  public void suspendApproval(
      RunExecutionContext execution, String id, List<Message> history, PendingTools pending) {
    var owner = execution.runContext();
    execution.checkActive();
    validate(owner);
    var request = findApproval.apply(owner.projectId(), id);
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
    var previous = store.get().resuming(owner.runId());
    store
        .get()
        .save(
            id,
            owner,
            execution.userRequest(),
            history,
            pending,
            execution.budgetSnapshot(),
            managed.containsKey(owner.runId()) || execution.hasParentReservation(),
            execution.protocolHistorySnapshot(),
            execution.progressSnapshot().connection());
    previous.ifPresent(old -> {
      retireContinuationGrant.accept(owner.projectId(), old.approvalId());
      store.get().state(old.approvalId(), "COMPLETED");
    });
    if (!registry.getObject().transition(owner.runId(), RunStatus.WAITING_APPROVAL, null)) {
      store.get().state(id, "CANCELLED");
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
    String verifiedInferenceDocument = null;
    if (approved) {
      var request = findApproval.apply(project, id);
      var saved = store.get().find(id);
      if (saved.isPresent()
          && saved.get().state().equals("WAITING_APPROVAL")
          && store.get().document(saved.get()).inference() != null) {
        // Protected payloads use their own transaction. Verify before the short decision
        // transaction, then compare the exact immutable document again before granting.
        verifyInferenceBinding(saved.get(), request, true);
        verifiedInferenceDocument = saved.get().document();
      }
    }
    String verifiedDocument = verifiedInferenceDocument;
    return transaction.execute(tx -> {
      var current = findApproval.apply(project, id);
      var entry = store.get().find(id);
      if (continueRun) {
        var saved =
            entry.orElseThrow(() ->
                new IllegalStateException(
                    "No saved invocation; approve normally and use /resume list for this older request"));
        var owner = store.get().document(saved).owner();
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
          throw new IllegalStateException("Approval expired; saved invocation was not executed");
        if (registry.getObject().get(owner.runId()).status() != RunStatus.WAITING_APPROVAL)
          throw new IllegalStateException("Run no longer waiting for approval");
      }
      if (approved)
        entry.ifPresent(saved -> {
          if (saved.state().equals("WAITING_APPROVAL")
              && store.get().document(saved).inference() != null
              && !java.util.Objects.equals(verifiedDocument, saved.document()))
            throw new IllegalArgumentException(
                "Inference approval content changed during decision");
          verifyInferenceBinding(saved, current, false);
        });
      var decided =
          current.status().equals("PENDING")
              ? decideApproval.apply(new ApprovalDecision(project, id, approved))
              : current;
      if (!decided.status().equals(approved ? "APPROVED" : "DENIED")
          && !(continueRun && decided.status().equals("CONSUMED")))
        throw new IllegalStateException("Approval already decided differently");
      if (continueRun && !store.get().queue(id))
        throw new IllegalStateException("Continuation acceptance conflict");
      if (!approved) entry.ifPresent(saved -> store.get().deny(id));
      return decided;
    });
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
  @Override
  public Saved restore(AgentRunContext owner) {
    var entry = store.get().resuming(owner.runId());
    if (entry.isPresent()) {
      if (!store.get().document(entry.get()).owner().equals(owner))
        throw new IllegalArgumentException("Continuation ownership mismatch");
      verifyInferenceBinding(
          entry.get(), findApproval.apply(owner.projectId(), entry.get().approvalId()), false);
      return store.get().saved(entry.get());
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
          .get()
          .resuming(execution.runContext().runId())
          .ifPresent(entry -> {
            retireContinuationGrant.accept(execution.runContext().projectId(), entry.approvalId());
            store.get().state(entry.approvalId(), "COMPLETED");
          });
    return waiting;
  }

  /** ツール利用の権限制御に関する終端を処理する。 */
  @Override
  public void terminal(AgentRunContext owner) {
    if (store.get().active().stream().anyMatch(entry -> entry.runId().equals(owner.runId()))) {
      var status = registry.getObject().get(owner.runId()).status();
      store.get().active().stream()
          .filter(entry -> entry.runId().equals(owner.runId()))
          .forEach(entry -> retireContinuationGrant.accept(owner.projectId(), entry.approvalId()));
      store
          .get()
          .finishRun(
              owner.runId(),
              status == RunStatus.COMPLETED
                  ? "COMPLETED"
                  : status == RunStatus.CANCELLED ? "CANCELLED" : "UNKNOWN");
    }
    var external = slack.getIfAvailable();
    if (external != null) external.terminal(owner);
  }

  /** 終端イベント公開前でも、実行結果から承認継続の結果を確定する。 */
  @Override
  public void terminal(AgentRunContext owner, ChatExecutionResult result) {
    if (store.get().active().stream().anyMatch(entry -> entry.runId().equals(owner.runId()))) {
      store.get().active().stream()
          .filter(entry -> entry.runId().equals(owner.runId()))
          .forEach(entry -> retireContinuationGrant.accept(owner.projectId(), entry.approvalId()));
      store
          .get()
          .finishRun(
              owner.runId(),
              result.success()
                  ? "COMPLETED"
                  : result.status() == ChatExecutionResult.Status.CANCELLED
                      ? "CANCELLED"
                      : "UNKNOWN");
    }
    var external = slack.getIfAvailable();
    if (external != null) external.terminal(owner);
  }

  /** 要求を後続の処理へ引き渡すため、案内を受け付ける。 */
  @Override
  public void acceptGuidance(AgentRunContext owner, List<Message> guidance) {
    if (guidance.isEmpty()) return;
    var active =
        store.get().active().stream().filter(e -> e.runId().equals(owner.runId())).toList();
    var inference =
        active.stream().filter(e -> store.get().document(e).inference() != null).toList();
    if (!inference.isEmpty()) {
      if (inference.size() != 1)
        throw new IllegalStateException("Ambiguous inference guidance target");
      replaceInferenceGuidance(owner, inference.getFirst(), guidance);
    } else if (!active.isEmpty()) store.get().guidance(owner, guidance);
    else {
      var external = slack.getIfAvailable();
      if (external != null) external.acceptGuidance(owner, guidance);
    }
  }

  private void replaceInferenceGuidance(
      AgentRunContext owner, ApprovalContinuationStore.Entry original, List<Message> guidance) {
    var document = store.get().document(original);
    var request = findApproval.apply(owner.projectId(), original.approvalId());
    if (!document.owner().equals(owner)
        || !Set.of("WAITING_APPROVAL", "QUEUED").contains(original.state())
        || registry.getObject().get(owner.runId()).status() != RunStatus.WAITING_APPROVAL
        || !clock.instant().isBefore(request.expiresAt())
        || !Set.of("PENDING", "APPROVED").contains(request.status()))
      throw new IllegalStateException("Inference is no longer awaiting editable approval");
    verifyInferenceBinding(original, request, true);
    var revised = store.get().inferenceGuidance(original, guidance);
    var replacement =
        inferenceGuard.requestRevisedApproval(owner, revised.connection(), revised.inference());
    if (replacement.id().equals(original.approvalId()))
      throw new IllegalStateException("Inference guidance did not change the input");
    try {
      var encoded =
          store
              .get()
              .prepareInference(
                  replacement.id(),
                  owner,
                  revised.request(),
                  revised.budget(),
                  revised.managedParent(),
                  revised.protocolHistory(),
                  revised.connection(),
                  revised.inference());
      transaction.executeWithoutResult(tx -> {
        var current = registry.getObject().get(owner.runId());
        if (!current.context().equals(owner)
            || current.status() != RunStatus.WAITING_APPROVAL
            || !store.get().cancelForInferenceGuidance(original))
          throw new IllegalStateException("Inference guidance target changed during preparation");
        var old = findApproval.apply(owner.projectId(), original.approvalId());
        var next = findApproval.apply(owner.projectId(), replacement.id());
        if (!Set.of("PENDING", "APPROVED").contains(old.status())
            || !Set.of("PENDING", "APPROVED").contains(next.status())
            || !clock.instant().isBefore(next.expiresAt()))
          throw new IllegalStateException("Inference guidance approval changed during preparation");
        inferenceGuard.verifySavedApproval(
            replacement.id(), owner, revised.connection(), revised.inference());
        store.get().insertPreparedInference(replacement.id(), owner, encoded);
        if (old.status().equals("PENDING"))
          decideApproval.apply(
              new ApprovalDecision(owner.projectId(), original.approvalId(), false));
        else retireContinuationGrant.accept(owner.projectId(), original.approvalId());
      });
    } catch (RuntimeException failed) {
      if (store.get().find(replacement.id()).isEmpty()) {
        try {
          var unclaimed = findApproval.apply(owner.projectId(), replacement.id());
          if (unclaimed.status().equals("PENDING"))
            decideApproval.apply(new ApprovalDecision(owner.projectId(), replacement.id(), false));
          else retireContinuationGrant.accept(owner.projectId(), replacement.id());
          store.get().discardPreparedInference(owner, replacement.id());
        } catch (RuntimeException retained) {
          org.slf4j.LoggerFactory.getLogger(ApprovalContinuationService.class)
              .warn("Unclaimed inference guidance receipt retained after cleanup failure");
        }
      }
      throw new IllegalStateException("Inference guidance replacement failed", failed);
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

  private void verifyInferenceBinding(
      ApprovalContinuationStore.Entry entry,
      ToolApprovalRepository.Request request,
      boolean verifyContent) {
    var document = store.get().document(entry);
    if (document.inference() == null) {
      if (request.tool().equals("inferenceExport"))
        throw new IllegalArgumentException("Inference approval binding unavailable");
      return;
    }
    var owner = document.owner();
    if (!request.projectId().equals(owner.projectId())
        || !request.sessionId().equals(owner.conversationId())
        || !request.runId().equals(owner.runId())
        || !entry.runId().equals(owner.runId()))
      throw new IllegalArgumentException("Inference approval ownership binding mismatch");
    validate(owner);
    inferenceGuard.verifySavedApproval(
        entry.approvalId(), owner, document.connection(), document.inference());
    if (verifyContent) store.get().saved(entry);
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
    for (var entry : store.get().active()) {
      try {
        var doc = store.get().document(entry);
        var owner = doc.owner();
        var current = registry.getObject().get(owner.runId());
        if (current.status() == RunStatus.CANCELLED) {
          retireContinuationGrant.accept(owner.projectId(), entry.approvalId());
          store.get().state(entry.approvalId(), "CANCELLED");
          notifyParent(owner, ChatExecutionResult.cancelled());
          continue;
        }
        if (entry.state().equals("RESUMING")) {
          if (!store.get().ownerAlive(entry)) {
            retireContinuationGrant.accept(owner.projectId(), entry.approvalId());
            store.get().state(entry.approvalId(), "UNKNOWN");
            if (registry.getObject().claimWaiting(owner))
              runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
            markCheckpoint(owner, "UNKNOWN");
          }
          continue;
        }
        if (router.getObject().containsRun(owner.projectId(), owner.runId())) continue;
        var request = findApproval.apply(owner.projectId(), entry.approvalId());
        if (!clock.instant().isBefore(request.expiresAt())) {
          store.get().state(entry.approvalId(), "EXPIRED");
          stop(owner, ChatExecutionResult.failed("Approval expired", "approval_expired"));
          continue;
        }
        if (request.status().equals("DENIED") || entry.state().equals("DENY_QUEUED")) {
          store.get().state(entry.approvalId(), "DENIED");
          stop(owner, ChatExecutionResult.cancelled());
          continue;
        }
        if (!entry.state().equals("QUEUED")
            || current.status() != RunStatus.WAITING_APPROVAL
            || router.getObject().containsRun(owner.projectId(), owner.runId())) continue;
        try {
          validate(owner);
          verifyInferenceBinding(entry, request, true);
        } catch (RuntimeException changed) {
          retireContinuationGrant.accept(owner.projectId(), entry.approvalId());
          store.get().state(entry.approvalId(), "UNKNOWN");
          if (registry.getObject().claimWaiting(owner))
            runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
          markCheckpoint(owner, "UNKNOWN");
          continue;
        }
        if (doc.managedParent() && !managed.containsKey(owner.runId())) {
          retireContinuationGrant.accept(owner.projectId(), entry.approvalId());
          store.get().state(entry.approvalId(), "UNKNOWN");
          if (registry.getObject().claimWaiting(owner))
            runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
          markCheckpoint(owner, "UNKNOWN");
          continue;
        }
        if (!registry.getObject().claimWaiting(owner) || !store.get().claim(entry.approvalId()))
          continue;
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
                            findApproval
                                .apply(owner.projectId(), entry.approvalId())
                                .expiresAt())) {
                      store.get().state(entry.approvalId(), "EXPIRED");
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
                    retireContinuationGrant.accept(owner.projectId(), entry.approvalId());
                    store.get().state(entry.approvalId(), "UNKNOWN");
                    runs.getObject().finishMissingTerminal(owner, RunStatus.UNKNOWN);
                    notifyParent(
                        owner,
                        ChatExecutionResult.failed(
                            "Approval continuation outcome unknown", "approval_outcome_unknown"));
                    return;
                  }
                  if (result.status() != ChatExecutionResult.Status.WAITING) {
                    retireContinuationGrant.accept(owner.projectId(), entry.approvalId());
                    store
                        .get()
                        .state(
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
        if (store
            .get()
            .find(entry.approvalId())
            .map(e -> e.state().equals("RESUMING"))
            .orElse(false)) {
          retireContinuationGrant.accept(
              store.get().document(entry).owner().projectId(), entry.approvalId());
          store.get().state(entry.approvalId(), "UNKNOWN");
          try {
            var owner = store.get().document(entry).owner();
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
    try {
      checkpointTransition.accept(owner, status);
    } catch (RuntimeException conflict) {
      /* Do not overwrite an active or changed checkpoint. */
    }
  }

  private static void updateUnleasedCheckpoint(
      dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository repository,
      AgentRunContext owner,
      String status) {
    repository
        .findByRun(owner.projectId(), owner.runId())
        .filter(saved ->
            saved.runId().equals(owner.runId())
                && saved.sessionId().equals(owner.conversationId())
                && !repository.leased(saved.projectId(), saved.taskId()))
        .ifPresent(saved -> {
          var fields = repository.fields(saved);
          fields.put("status", status);
          fields.put("interruptionReason", "approval_" + status.toLowerCase(Locale.ROOT));
          repository.save(
              repository.fields(fields),
              saved.revision(),
              "approval-stop:" + owner.runId() + ":" + status);
        });
  }

  /** 状態の変化を利用先へ伝えるため、親を通知する。 */
  private void notifyParent(AgentRunContext owner, ChatExecutionResult result) {
    var parent = managed.remove(owner.runId());
    var external = slack.getIfAvailable();
    if (external != null) external.detachParent(owner);
    if (parent != null) parent.completed().accept(result);
  }

  private record ApprovalDecision(String project, String id, boolean approved) {}
}
