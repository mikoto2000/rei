package dev.mikoto2000.rei.externalagent;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.event.ExternalAgentLifecyclePayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * 外部エージェント委譲のユースケースを実現する。
 *
 * <p>Owns authorization, request selection, one-shot run budget and terminal event semantics.
 */
@Service
public class ExternalAgentDelegationService implements AutoCloseable {
  /** 対象範囲。 */
  private dev.mikoto2000.rei.review.ReviewCoverageLedger coverage;

  /** 外部エージェントへの委譲で使用する対象範囲を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void setCoverage(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.review.ReviewCoverageLedger>
          ledger,
      @org.springframework.beans.factory.annotation.Value(
              "${rei.tool-result-delivery.review-ledger.enabled:true}")
          boolean enabled) {
    coverage = enabled ? ledger.getIfAvailable() : null;
  }

  /**
   * 外部エージェントへの委譲で受け渡す並列要求を表す。
   *
   * @param requestId 要求の識別子
   * @param task タスク
   * @param target 対象
   * @param context 文脈
   */
  public record ParallelRequest(String requestId, String task, String target, String context) {}

  /** 外部エージェントへの委譲における並列状態の選択肢。 */
  public enum ParallelStatus {
    /** 完了。 */
    COMPLETED,
    /** 部分的な結果。 */
    PARTIAL,
    /** タイムアウト。 */
    TIMEOUT,
    /** 拒否済み。 */
    REJECTED,
    /** 使用中。 */
    BUSY
  }

  /**
   * 外部エージェントへの委譲で受け渡す並列項目を表す。
   *
   * @param requestId 要求の識別子
   * @param result 結果
   */
  public record ParallelItem(String requestId, ExternalAgentResult result) {}

  /**
   * 外部エージェントへの委譲で受け渡す並列結果を表す。
   *
   * @param status 状態
   * @param items 項目一覧
   * @param message メッセージ
   */
  public record ParallelResult(ParallelStatus status, List<ParallelItem> items, String message) {
    /** 受け取った情報からParallelResultを作成する。 */
    public ParallelResult {
      items = List.copyOf(items);
    }
  }

  /** 並列受付。 */
  private final Semaphore parallelAdmission = new Semaphore(1);

  /** 並列実行枠。 */
  private final ThreadPoolExecutor parallelPool =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          Thread.ofPlatform().daemon().name("parallel-codex-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  /** 終了済み。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 活動中並列。 */
  private final AtomicReference<ParallelPermit> activeParallel = new AtomicReference<>();

  /** 外部エージェントへの委譲に必要な並列利用枠を扱う。 */
  private static final class ParallelPermit {
    /** レビュー識別子一覧。 */
    final ConcurrentMap<String, String> reviewIds = new ConcurrentHashMap<>();

    /** 停止済み。 */
    final AtomicReference<dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException> stopped =
        new AtomicReference<>();

    /** 実行。 */
    final RunExecutionContext run;

    /** キャンセル済み。 */
    final AtomicBoolean cancelled = new AtomicBoolean();

    /** 待機中モデル一覧。 */
    final AtomicInteger pendingModels = new AtomicInteger();

    /** 予算。 */
    final dev.mikoto2000.rei.llm.ModelCallBudget budget;

    /** 指定された値や利用先を使って、ParallelPermitを初期化する。 */
    ParallelPermit(RunExecutionContext run) {
      this.run = run;
      var parent = run.modelCallBudget();
      budget =
          new dev.mikoto2000.rei.llm.ModelCallBudget() {
            /** 外部エージェントへの委譲の操作を実行する。 */
            public void run() {
              if (cancelled.get() || Thread.currentThread().isInterrupted())
                throw new CancellationException();
              parent.run();
              pendingModels.incrementAndGet();
            }

            /** トークン数による実行予算の制限が有効かどうかを返す。 */
            public boolean tokenLimitEnabled() {
              return parent.tokenLimitEnabled();
            }

            /** 使用したトークン数を実行予算へ記録する。 */
            public void recordTotalTokens(Integer tokens) {
              try {
                parent.recordTotalTokens(tokens);
              } finally {
                pendingModels.getAndUpdate(value -> Math.max(0, value - 1));
              }
            }
          };
    }
  }

  /** 外部エージェントへの委譲で利用した資源や登録を解放し、利用を終了する。 */
  @jakarta.annotation.PreDestroy
  @Override
  public void close() {
    closed.set(true);
    var permit = activeParallel.get();
    if (permit != null) {
      permit.cancelled.set(true);
      permit.run.cancel();
    }
    parallelPool.shutdownNow();
    try {
      parallelPool.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
  }

  /** 実行先。 */
  private final ExternalAgentExecutor executor;

  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 作業中集合。 */
  private final Optional<WorkingSet> workingSet;

  /** 履歴。 */
  private ExternalReviewRepository history;

  /** 委譲案内。 */
  private dev.mikoto2000.rei.chatgpt.ChatGptDelegationGuidance delegationGuidance;

  /** 外部エージェントへの委譲で使用する委譲案内を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDelegationGuidance(dev.mikoto2000.rei.chatgpt.ChatGptDelegationGuidance guidance) {
    delegationGuidance = guidance;
  }

  /** モデル予算設定。 */
  private CodexProperties modelBudgetProperties = new CodexProperties();

  /** Claude設定。 */
  private ClaudeCodeProperties claudeProperties = new ClaudeCodeProperties();

  /** 外部エージェントへの委譲に関するClaude設定を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void claudeProperties(ClaudeCodeProperties properties) {
    this.claudeProperties = properties;
  }

  /** 実装要求一覧。 */
  private org.springframework.beans.factory.ObjectProvider<ImplementationRequestService>
      implementationRequests;

  /** 外部エージェントへの委譲に関する実装要求一覧を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void implementationRequests(
      org.springframework.beans.factory.ObjectProvider<ImplementationRequestService> requests) {
    this.implementationRequests = requests;
  }

  /** 隔離した作業環境で実装を依頼する窓口。 */
  private IsolatedImplementationService implementations;

  /** 外部エージェントへの委譲に関するimplementationsを処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void implementations(IsolatedImplementationService service) {
    this.implementations = service;
  }

  /** 外部エージェントへの委譲に関するモデル予算設定を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void modelBudgetProperties(CodexProperties properties) {
    this.modelBudgetProperties = properties;
  }

  /** 変更一覧。 */
  private dev.mikoto2000.rei.core.TextChangeSetService changes;

  /** 外部エージェントへの委譲に関する変更集合一覧を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void changeSets(dev.mikoto2000.rei.core.TextChangeSetService changes) {
    this.changes = changes;
  }

  /** 外部エージェントへの委譲に関するレビュー履歴を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  void reviewHistory(ExternalReviewRepository history) {
    this.history = history;
  }

  /** 指定された値や利用先を使って、ExternalAgentDelegationServiceを初期化する。 */
  public ExternalAgentDelegationService(
      ExternalAgentExecutor executor,
      CommandCancellationService cancellation,
      AgentEventFactory events,
      AgentEventPublisher publisher,
      Optional<WorkingSet> workingSet) {
    this.executor = executor;
    this.cancellation = cancellation;
    this.events = events;
    this.publisher = publisher;
    this.workingSet = workingSet;
  }

  /** 外部エージェントへの委譲で用いるレビューを返す。 */
  public ExternalAgentResult review(
      RunExecutionContext run, String task, String target, String decisions) {
    return review(run, task, target, decisions, null, false, false);
  }

  /** 外部エージェントへの委譲で用いるimplementを返す。 */
  public ExternalAgentResult implement(RunExecutionContext run, String target) {
    return implement(run, ExternalAgentRequest.Agent.CODEX, target);
  }

  /** 外部エージェントへの委譲で用いるimplementを返す。 */
  public ExternalAgentResult implement(
      RunExecutionContext run, ExternalAgentRequest.Agent agent, String target) {
    boolean codex = agent == ExternalAgentRequest.Agent.CODEX;
    boolean enabled =
        codex
            ? modelBudgetProperties.isEnabled() && modelBudgetProperties.isImplementationEnabled()
            : agent == ExternalAgentRequest.Agent.CLAUDE
                && claudeProperties.isEnabled()
                && claudeProperties.isImplementationEnabled();
    if (closed.get() || run == null || implementations == null || !enabled)
      return ExternalAgentResult.rejected(
          "Implementation requires selected-provider administrator opt-in and a saved implementation service");
    try {
      var command = ExternalAgentCommandRequest.parse(run.userRequest());
      if (!command.agent().equals(agent.name().toLowerCase(Locale.ROOT))
          || !command.action().equals("implement")
          || !Objects.equals(command.target(), target))
        return ExternalAgentResult.rejected(
            "Explicit implementation request for the selected provider and target in this Run required");
      if (codex && implementationRequests != null) {
        var draft = implementationRequests.getObject().prepare(run, null, null);
        return new ExternalAgentResult(
            ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS,
            ImplementationSpecificationValidator.serialize(draft),
            List.of(),
            List.of("Confirm requirements using the saved clarification requestId"),
            0,
            null,
            "");
      }
      if (codex)
        return new ExternalAgentResult(
            ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS,
            "NEEDS_CLARIFICATION: Confirm objective, concrete instructions, existing target, allowedPaths, constraints and acceptanceCriteria for "
                + target
                + ". Then prepareCodexImplementation and execute its saved requestId/version/hash through the common implementation service.",
            List.of(),
            List.of("Target-only implementation no longer starts Codex"),
            0,
            null,
            "");
      var owner = run.runContext();
      if (owner == null
          || owner.projectId() == null
          || owner.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE)
        return ExternalAgentResult.rejected("Exclusive current Project required");
      run.checkToolPermission(
          codex ? "requestCodexImplementation" : "requestClaudeCodeImplementation", target);
      Path parentRoot = owner.projectRoot().toRealPath();
      String relativeTarget =
          parentRoot.relativize(ExternalAgentRequest.resolveTarget(parentRoot, target)).toString();
      String recipe =
          codex
              ? modelBudgetProperties.getImplementationTestCommand()
              : claudeProperties.getImplementationTestCommand();
      int seconds =
          codex
              ? modelBudgetProperties.getImplementationTestTimeoutSeconds()
              : claudeProperties.getImplementationTestTimeoutSeconds();
      if (recipe == null
          || recipe.isBlank()
          || recipe.length() > 4096
          || seconds < 1
          || seconds > 60)
        return ExternalAgentResult.rejected(
            "Administrator-selected bounded implementation test recipe required");
      if (!run.claimExternalDelegation())
        return ExternalAgentResult.rejected("Only one external delegation per Run");
      var flag = new AtomicBoolean(run.isCancelled());
      var hook =
          cancellation.onCancel(
              owner.runId(),
              () -> {
                flag.set(true);
                run.cancel();
              });
      try {
        var receipt =
            implementations.implement(
                owner,
                agent,
                relativeTarget,
                recipe,
                seconds,
                () -> flag.get() || run.isCancelled(),
                (tree, manifest) -> {
                  run.checkActive();
                  var selected = ExternalAgentRequest.resolveTarget(tree, relativeTarget);
                  var snapshot =
                      ExternalAgentSourceSnapshot.snapshot(
                          tree,
                          selected,
                          () -> flag.get() || run.isCancelled(),
                          System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos());
                  String context =
                      new com.fasterxml.jackson.databind.ObjectMapper()
                          .writeValueAsString(
                              Map.of("sourceSnapshot", snapshot, "manifest", manifest));
                  var request =
                      new ExternalAgentRequest(
                          agent,
                          ExternalAgentRequest.Action.IMPLEMENT,
                          bounded(run.userRequest(), 4000),
                          tree,
                          selected,
                          context,
                          owner.runId(),
                          UUID.randomUUID().toString());
                  var result =
                      executor.execute(
                          request, () -> flag.get() || run.isCancelled(), run.modelCallBudget());
                  run.checkActive();
                  if (!result.success() || result.implementation() == null)
                    throw new java.io.IOException(
                        agent.name()
                            + " implementation proposal "
                            + result.status()
                            + ": "
                            + bounded(result.summary(), 800));
                  return result.implementation();
                });
        return implementationResult(receipt);
      } finally {
        hook.dispose();
      }
    } catch (java.util.concurrent.CancellationException cancelled) {
      run.cancel();
      throw cancelled;
    } catch (java.io.IOException failure) {
      return new ExternalAgentResult(
          ExternalAgentResult.Status.FAILED,
          "Implementation storage or process unavailable; inspect receipts before retry",
          List.of(),
          List.of(),
          0,
          null,
          "");
    } catch (IllegalArgumentException invalid) {
      return ExternalAgentResult.rejected(invalid.getMessage());
    }
  }

  /**
   * 依頼された操作を進めるため、仕様を実行する。
   *
   * <p>Only the persisted request service calls this; no model-supplied approved-request DTO.
   */
  IsolatedImplementationService.Receipt executeSpecification(
      RunExecutionContext run,
      String requestId,
      ImplementationSpecificationValidator.Validated validated,
      String base,
      String recipe,
      int seconds) {
    if (closed.get()
        || run == null
        || implementations == null
        || !modelBudgetProperties.isEnabled()
        || !modelBudgetProperties.isImplementationEnabled())
      throw new IllegalArgumentException("Implementation opt-in/service unavailable");
    var owner = run.runContext();
    if (owner == null
        || owner.projectId() == null
        || owner.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Exclusive current Project required");
    if (!Objects.equals(recipe, modelBudgetProperties.getImplementationTestCommand())
        || seconds != modelBudgetProperties.getImplementationTestTimeoutSeconds())
      throw new IllegalArgumentException("Administrator test recipe changed");
    run.checkActive();
    run.checkModelTokenBudget();
    if (!run.claimExternalDelegation())
      throw new IllegalArgumentException("Only one external delegation per Run");
    var flag = new AtomicBoolean(run.isCancelled());
    var hook =
        cancellation.onCancel(
            owner.runId(),
            () -> {
              flag.set(true);
              run.cancel();
            });
    try {
      var specification = validated.specification();
      return implementations.implementSpecification(
          owner,
          requestId,
          specification.target(),
          specification.allowedPaths(),
          base,
          recipe,
          seconds,
          () -> flag.get() || run.isCancelled(),
          (tree, manifest) -> {
            run.checkActive();
            var selected = ExternalAgentRequest.resolveTarget(tree, specification.target());
            var snapshot =
                ExternalAgentSourceSnapshot.snapshot(
                    tree,
                    selected,
                    () -> flag.get() || run.isCancelled(),
                    System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos());
            String context =
                ImplementationSpecificationValidator.serialize(
                    Map.of(
                        "approvedAcceptanceCriteria",
                        specification.acceptanceCriteria(),
                        "requirementReferences",
                        specification.references(),
                        "allowedPaths",
                        specification.allowedPaths(),
                        "specificationSha256",
                        validated.sha256(),
                        "sourceSnapshot",
                        snapshot,
                        "manifest",
                        manifest));
            var request =
                new ExternalAgentRequest(
                    ExternalAgentRequest.Agent.CODEX,
                    ExternalAgentRequest.Action.IMPLEMENT,
                    validated.task(),
                    tree,
                    selected,
                    context,
                    owner.runId(),
                    requestId);
            var result =
                executor.execute(
                    request, () -> flag.get() || run.isCancelled(), run.modelCallBudget());
            run.checkActive();
            if (!result.success() || result.implementation() == null)
              throw new java.io.IOException(
                  "Codex proposal " + result.status() + ": " + bounded(result.summary(), 800));
            return result.implementation();
          });
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(
          "Implementation receipt unavailable; inspect request and worktree before any retry",
          failure);
    } finally {
      hook.dispose();
    }
  }

  /** 外部エージェントへの委譲で用いる実装を返す。 */
  public IsolatedImplementationService.Receipt implementation(RunExecutionContext run, String id) {
    if (run == null || implementations == null)
      throw new IllegalArgumentException("Current Project and implementation storage required");
    try {
      return implementations.get(run.runContext(), id);
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Implementation receipt unavailable");
    }
  }

  /** 関連する情報をまとめて扱えるよう、実装を統合する。 */
  public ExternalAgentResult mergeImplementation(RunExecutionContext run, String id, String hash) {
    if (run == null || implementations == null)
      return ExternalAgentResult.rejected("Current Project and implementation storage required");
    run.checkToolPermission("mergeExternalImplementation", id + " " + hash);
    try {
      return implementationResult(
          implementations.merge(run.runContext(), id, hash, run.userRequest()));
    } catch (java.io.IOException unavailable) {
      return ExternalAgentResult.rejected("Implementation receipt unavailable");
    } catch (IllegalArgumentException rejected) {
      return ExternalAgentResult.rejected(rejected.getMessage());
    }
  }

  /** 外部エージェントへの委譲で用いる実装事前確認を返す。 */
  public IsolatedImplementationService.Preview implementationPreview(
      RunExecutionContext run, String id) {
    if (run == null || implementations == null)
      throw new IllegalArgumentException("Current Project and implementation storage required");
    try {
      return implementations.preview(run.runContext(), id);
    } catch (java.io.IOException unavailable) {
      throw new IllegalArgumentException("Implementation diff unavailable");
    }
  }

  /** 外部エージェントへの委譲で用いる実装結果を返す。 */
  private ExternalAgentResult implementationResult(IsolatedImplementationService.Receipt receipt)
      throws java.io.IOException {
    boolean success = Set.of("READY_FOR_APPROVAL", "MERGED").contains(receipt.status());
    return new ExternalAgentResult(
        success
            ? ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS
            : ExternalAgentResult.Status.FAILED,
        new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules()
            .writeValueAsString(receipt),
        List.of(),
        List.of(
            "External implementation requires independent semantic evaluation and explicit receipt/hash approval; no automatic merge"),
        0,
        null,
        "",
        receipt.id());
  }

  /** 外部エージェントへの委譲で用いるレビューを返す。 */
  public ExternalAgentResult review(
      RunExecutionContext run,
      ExternalAgentRequest.Agent agent,
      String task,
      String target,
      String decisions) {
    if (agent == null) return ExternalAgentResult.rejected("External review provider required");
    return review(run, task, target, decisions, null, false, false, null, null, agent);
  }

  /** 外部エージェントへの委譲で用いるrereviewを返す。 */
  public ExternalAgentResult rereview(
      RunExecutionContext run, String previousId, String task, String decisions) {
    if (previousId == null || previousId.isBlank())
      return ExternalAgentResult.rejected("Previous review ID required");
    return review(run, task, null, decisions, previousId, false, false);
  }

  /** 外部エージェントへの委譲で用いるcontinueレビューを返す。 */
  public ExternalAgentResult continueReview(
      RunExecutionContext run, String previousId, String task, String decisions) {
    if (previousId == null || previousId.isBlank())
      return ExternalAgentResult.rejected("Previous review ID required");
    return review(run, task, null, decisions, previousId, true, false);
  }

  /** 外部エージェントへの委譲で用いるcontinueレビューを返す。 */
  public ExternalAgentResult continueReview(
      RunExecutionContext run,
      ExternalAgentRequest.Agent agent,
      String previousId,
      String task,
      String decisions) {
    if (agent == null || previousId == null || previousId.isBlank())
      return ExternalAgentResult.rejected("Provider and previous review ID required");
    if (agent == ExternalAgentRequest.Agent.CLAUDE
        && (run == null
            || !ExternalAgentAuthorization.explicitContinuationRequest(run.userRequest(), agent)))
      return ExternalAgentResult.rejected("Explicit Claude review continuation request required");
    return review(run, task, null, decisions, previousId, true, false, null, null, agent);
  }

  /** 後続の判断で選べる候補を用意するため、修正を提案する。 */
  public ExternalAgentResult proposeFix(
      RunExecutionContext run, String previousId, String task, String decisions) {
    if (previousId == null || previousId.isBlank())
      return ExternalAgentResult.rejected("Previous review ID required");
    return review(run, task, null, decisions, previousId, false, true);
  }

  /** 後続の判断で選べる候補を用意するため、修正を提案する。 */
  public ExternalAgentResult proposeFix(
      RunExecutionContext run,
      ExternalAgentRequest.Agent agent,
      String previousId,
      String task,
      String decisions) {
    if (agent == null || previousId == null || previousId.isBlank())
      return ExternalAgentResult.rejected("Provider and previous review ID required");
    return review(run, task, null, decisions, previousId, false, true, null, null, agent);
  }

  /** 外部エージェントへの委譲で用いるレビュー並列を返す。 */
  public ParallelResult reviewParallel(RunExecutionContext run, List<ParallelRequest> requests) {
    return reviewParallel(run, ExternalAgentRequest.Agent.CODEX, requests);
  }

  /** 外部エージェントへの委譲で用いるレビュー並列を返す。 */
  public ParallelResult reviewParallel(
      RunExecutionContext run, ExternalAgentRequest.Agent agent, List<ParallelRequest> requests) {
    boolean enabled =
        agent == ExternalAgentRequest.Agent.CODEX
            ? modelBudgetProperties.isParallelReviewEnabled()
            : agent == ExternalAgentRequest.Agent.CLAUDE
                && claudeProperties.isEnabled()
                && claudeProperties.isParallelReviewEnabled();
    if (closed.get()
        || !enabled
        || run == null
        || !ExternalAgentAuthorization.explicitParallelRequest(run.userRequest(), agent))
      return new ParallelResult(
          ParallelStatus.REJECTED,
          List.of(),
          "Explicit parallel review request for the selected provider and opt-in configuration required");
    List<ParallelRequest> selected;
    try {
      var owner = run.runContext();
      if (owner == null
          || owner.projectId() == null
          || !Files.isDirectory(owner.projectRoot())
          || requests == null
          || requests.size() < 1
          || requests.size() > 4) throw new IllegalArgumentException();
      selected = List.copyOf(requests);
      var ids = new HashSet<String>();
      for (var request : selected) {
        if (request.requestId() == null
            || !request.requestId().matches("[A-Za-z0-9_-]{1,64}")
            || !ids.add(request.requestId())
            || request.task() == null
            || request.task().isBlank()
            || request.task().length() > 4000
            || request.context() != null && request.context().length() > 6000
            || request.target() != null && request.target().length() > 1024)
          throw new IllegalArgumentException();
        ExternalAgentRequest.resolveTarget(owner.projectRoot(), request.target());
      }
    } catch (RuntimeException invalid) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(invalid);
      return new ParallelResult(
          ParallelStatus.REJECTED,
          List.of(),
          "Require at most four valid independent review requests in the current Project");
    }
    if (!parallelAdmission.tryAcquire())
      return new ParallelResult(ParallelStatus.BUSY, List.of(), "Parallel review batch is busy");
    var futures = new ArrayList<Future<ExternalAgentResult>>();
    var permit = new ParallelPermit(run);
    ParallelStatus status = ParallelStatus.COMPLETED;
    try {
      if (!run.claimExternalDelegation())
        return new ParallelResult(
            ParallelStatus.REJECTED,
            List.of(),
            "Only one external delegation or batch is allowed per Run");
      activeParallel.set(permit);
      long deadline =
          System.nanoTime()
              + (agent == ExternalAgentRequest.Agent.CODEX
                      ? modelBudgetProperties.getParallelReviewTimeout()
                      : claudeProperties.getParallelReviewTimeout())
                  .toNanos();
      for (var request : selected)
        futures.add(
            parallelPool.submit(
                () -> {
                  try {
                    return review(
                        run,
                        request.task(),
                        request.target(),
                        request.context(),
                        null,
                        false,
                        false,
                        permit,
                        request.requestId(),
                        agent);
                  } catch (dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped) {
                    permit.stopped.compareAndSet(null, stopped);
                    permit.cancelled.set(true);
                    throw stopped;
                  }
                }));
      for (var future : futures) {
        while (!future.isDone()) {
          if (permit.stopped.get() != null) throw permit.stopped.get();
          run.checkActive();
          long left = deadline - System.nanoTime();
          if (left <= 0) {
            status = ParallelStatus.TIMEOUT;
            break;
          }
          try {
            future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
          } catch (TimeoutException waiting) {
            continue;
          } catch (ExecutionException done) {
            break;
          }
        }
        if (status == ParallelStatus.TIMEOUT) break;
        if (permit.stopped.get() != null) throw permit.stopped.get();
        parallelOutcome(future);
      }
      if (status == ParallelStatus.TIMEOUT) permit.cancelled.set(true);
      var items = new ArrayList<ParallelItem>();
      for (int i = 0; i < selected.size(); i++) {
        var future = futures.get(i);
        ExternalAgentResult result;
        if (future.isDone()) result = parallelOutcome(future);
        else
          result =
              new ExternalAgentResult(
                  ExternalAgentResult.Status.TOTAL_TIMEOUT,
                  "Parallel batch deadline reached; inspect saved review outcomes",
                  List.of(),
                  List.of(),
                  0,
                  null,
                  "",
                  permit.reviewIds.get(selected.get(i).requestId()));
        if (!result.success() && status == ParallelStatus.COMPLETED)
          status = ParallelStatus.PARTIAL;
        items.add(new ParallelItem(selected.get(i).requestId(), result.forEvaluation()));
      }
      return new ParallelResult(
          status, items, "Independent read-only results; findings require separate evaluation");
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      run.cancel();
      throw new CancellationException("Parallel review cancelled");
    } catch (RejectedExecutionException busy) {
      return new ParallelResult(
          ParallelStatus.BUSY, List.of(), "Parallel review workers unavailable");
    } finally {
      permit.cancelled.set(true);
      for (var future : futures) if (!future.isDone()) future.cancel(true);
      activeParallel.compareAndSet(permit, null);
      parallelAdmission.release();
      if (permit.pendingModels.get() > 0 && permit.budget.tokenLimitEnabled() && !run.isCancelled())
        run.modelCallBudget().recordTotalTokens(null);
    }
  }

  /** 外部エージェントへの委譲で用いる並列最終結果を返す。 */
  private ExternalAgentResult parallelOutcome(Future<ExternalAgentResult> future)
      throws InterruptedException {
    try {
      return future.get();
    } catch (ExecutionException error) {
      var cause = error.getCause();
      if (cause instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped)
        throw stopped;
      if (cause instanceof CancellationException cancelled) throw cancelled;
      if (cause instanceof Error fatal) throw fatal;
      return new ExternalAgentResult(
          ExternalAgentResult.Status.FAILED,
          "Parallel review failed; inspect saved outcomes",
          List.of(),
          List.of(),
          0,
          null,
          "");
    }
  }

  /** 外部エージェントへの委譲で用いるレビューを返す。 */
  private ExternalAgentResult review(
      RunExecutionContext run,
      String task,
      String target,
      String decisions,
      String previousId,
      boolean continuation,
      boolean fixProposal) {
    return review(run, task, target, decisions, previousId, continuation, fixProposal, null, null);
  }

  /** 外部エージェントへの委譲で用いるレビューを返す。 */
  private ExternalAgentResult review(
      RunExecutionContext run,
      String task,
      String target,
      String decisions,
      String previousId,
      boolean continuation,
      boolean fixProposal,
      ParallelPermit permit,
      String parallelRequestId) {
    return review(
        run,
        task,
        target,
        decisions,
        previousId,
        continuation,
        fixProposal,
        permit,
        parallelRequestId,
        ExternalAgentRequest.Agent.CODEX);
  }

  /** 外部エージェントへの委譲で用いるレビューを返す。 */
  private ExternalAgentResult review(
      RunExecutionContext run,
      String task,
      String target,
      String decisions,
      String previousId,
      boolean continuation,
      boolean fixProposal,
      ParallelPermit permit,
      String parallelRequestId,
      ExternalAgentRequest.Agent agent) {
    String provider = agent.name().toLowerCase(Locale.ROOT);
    String providerLabel = agent == ExternalAgentRequest.Agent.CODEX ? "Codex" : "Claude Code";
    if (permit != null
        && (permit.run != run || permit.cancelled.get() || Thread.currentThread().isInterrupted()))
      return new ExternalAgentResult(
          ExternalAgentResult.Status.CANCELLED,
          "Parallel review cancelled before execution",
          List.of(),
          List.of(),
          0,
          null,
          "");
    if (run == null
        || !(fixProposal
            ? ExternalAgentAuthorization.explicitFixProposalRequest(run.userRequest(), agent)
            : ExternalAgentAuthorization.explicitRequest(run.userRequest(), agent)))
      return ExternalAgentResult.rejected(
          fixProposal
              ? providerLabel
                  + " fix proposals require an explicit request for a fix proposal from this provider in this Run; tool arguments and an ordinary review request cannot authorize it."
              : provider
                  + " requires an explicit user request in this Run. Tool arguments cannot authorize another provider."
                  + (delegationGuidance == null || run == null
                      ? ""
                      : delegationGuidance.rejectionHint(run.userRequest())));
    var owner = run.runContext();
    if (owner == null || owner.projectId() == null || !Files.isDirectory(owner.projectRoot()))
      return ExternalAgentResult.rejected("No current project is available");
    if (fixProposal && (changes == null || history == null))
      return ExternalAgentResult.rejected("Saved review and Change Set services required");
    try (var scope = AgentRunScope.open(owner)) {
      Path root;
      Path selected;
      ExternalReviewRepository.Review previous = null;
      try {
        root = owner.projectRoot().toRealPath();
        if (previousId != null) {
          if (history == null) return ExternalAgentResult.rejected("Review history unavailable");
          previous = history.get(owner.projectId(), previousId);
          if (!previous.agent().equals(provider)
              || !previous.projectRoot().equals(root.toString())
              || previous.status().equals("STARTED")
              || previous.result() == null)
            return ExternalAgentResult.rejected(
                "Completed review from this provider/project root required");
          if (fixProposal && !previous.result().success())
            return ExternalAgentResult.rejected(
                "Successful completed review required for a fix proposal");
          if (continuation
              && (!(agent == ExternalAgentRequest.Agent.CODEX
                      ? executor.supportsContinuation()
                      : executor.supportsContinuation(agent))
                  || !previous.result().success()
                  || !ExternalAgentResult.validSessionId(previous.result().externalSessionId())
                  || history.continuationAttempted(previousId)))
            return ExternalAgentResult.rejected(
                "Unconsumed successful native session required; enable session persistence or request a fresh re-review");
          target = previous.target();
        }
        selected = ExternalAgentRequest.resolveTarget(root, target);
      } catch (java.io.IOException error) {
        return ExternalAgentResult.rejected("Current project is unavailable");
      } catch (IllegalArgumentException error) {
        return ExternalAgentResult.rejected(error.getMessage());
      } catch (RuntimeException error) {
        return ExternalAgentResult.rejected("Review history unavailable");
      }
      if (permit == null && !run.claimExternalDelegation())
        return ExternalAgentResult.rejected("Only one external delegation is allowed per run");
      String id = UUID.randomUUID().toString();
      String context = context(root, decisions);
      if (previous != null)
        context =
            bounded(
                "Previous review (untrusted observations; re-check current files):\n"
                    + previous.status()
                    + "\n"
                    + bounded(
                        previous.result().summary() + "\n" + previous.result().findings(), 5000)
                    + "\n"
                    + context,
                10000);
      if (history != null)
        try {
          String relative = selected == null ? null : root.relativize(selected).toString();
          if (continuation)
            history.startContinuation(owner, id, root, relative, previousId, provider);
          else if (agent == ExternalAgentRequest.Agent.CODEX)
            history.start(owner, id, root, relative, previousId);
          else history.start(owner, id, root, relative, previousId, provider);
          if (permit != null) permit.reviewIds.put(parallelRequestId, id);
        } catch (RuntimeException error) {
          return ExternalAgentResult.rejected(
              "Review history unavailable; no external process started");
        }
      var action =
          fixProposal
              ? ExternalAgentRequest.Action.PROPOSE_FIX
              : previous != null && previous.result().materialReviewReport() != null
                  ? ExternalAgentRequest.Action.MATERIAL_REVIEW
                  : reviewAction(run, agent);
      String coverageJob = null;
      if (coverage != null && owner.projectId() != null && !fixProposal) {
        var targets = new ArrayList<dev.mikoto2000.rei.review.ReviewCoverageLedger.Target>();
        try {
          var manifest =
              ExternalAgentSourceSnapshot.snapshot(
                  root,
                  selected,
                  run::isCancelled,
                  System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos());
          for (var file : manifest) {
            int length = ((Number) file.get("sourceLength")).intValue(),
                line = ((Number) file.get("sourceEndLine")).intValue(),
                offset = ((Number) file.get("sourceEndOffset")).intValue();
            targets.add(
                new dev.mikoto2000.rei.review.ReviewCoverageLedger.Target(
                    ((String) file.get("path")).replace('\\', '/'),
                    (String) file.get("sha256"),
                    line,
                    List.of(
                        new dev.mikoto2000.rei.review.ReviewCoverageLedger.Span(
                            0, length, 1, line, 0, offset))));
          }
        } catch (java.io.IOException unavailable) {
          targets.clear();
        }
        var job =
            coverage.createExternal(
                dev.mikoto2000.rei.review.ReviewTools.scope(owner),
                "External "
                    + provider
                    + " review "
                    + id
                    + " target="
                    + Objects.toString(selected, root.toString())
                    + " focus="
                    + bounded(task, 2000),
                "external-review-v1",
                targets);
        coverageJob = job.id();
      }
      var request =
          new ExternalAgentRequest(
              agent,
              action,
              bounded(run.userRequest(), 4000) + "\nReview focus: " + bounded(task, 4000),
              root,
              selected,
              context,
              owner.runId(),
              id,
              continuation ? previous.result().externalSessionId() : null);
      AtomicBoolean cancelled = new AtomicBoolean(run.isCancelled());
      var hook = cancellation.onCancel(owner.runId(), () -> cancelled.set(true));
      publisher.publish(
          events
              .delegation(
                  AgentEventType.DELEGATION_STARTED,
                  owner.runId(),
                  new ExternalAgentLifecyclePayload(
                      id,
                      provider,
                      action.name().toLowerCase(Locale.ROOT),
                      "STARTED",
                      "review",
                      0,
                      null))
              .withOwnership(owner));
      ExternalAgentResult result;
      dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped = null;
      long start = System.nanoTime();
      try {
        result =
            permit != null
                ? executor.execute(
                    request,
                    () ->
                        cancelled.get()
                            || run.isCancelled()
                            || permit.cancelled.get()
                            || Thread.currentThread().isInterrupted(),
                    permit.budget)
                : agent == ExternalAgentRequest.Agent.CLAUDE
                        || modelBudgetProperties.isInheritRunModelBudget()
                    ? executor.execute(
                        request, () -> cancelled.get() || run.isCancelled(), run.modelCallBudget())
                    : executor.execute(request, () -> cancelled.get() || run.isCancelled());
        if (fixProposal && result.success() && !cancelled.get() && !run.isCancelled())
          result = stageFix(owner, root, selected, result);
      } catch (dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException error) {
        stopped = error;
        result =
            new ExternalAgentResult(
                ExternalAgentResult.Status.FAILED,
                providerLabel + " review stopped by model budget",
                List.of(),
                List.of(),
                0,
                null,
                "");
      } catch (java.util.concurrent.CancellationException error) {
        result =
            new ExternalAgentResult(
                ExternalAgentResult.Status.CANCELLED,
                providerLabel + " review cancelled",
                List.of(),
                List.of(),
                0,
                null,
                "");
      } catch (RuntimeException error) {
        result =
            new ExternalAgentResult(
                ExternalAgentResult.Status.FAILED,
                providerLabel + " review could not be completed",
                List.of(),
                List.of(),
                0,
                null,
                "");
      } finally {
        hook.dispose();
      }
      boolean wasCancelled =
          cancelled.get()
              || run.isCancelled()
              || result.status() == ExternalAgentResult.Status.CANCELLED;
      if (wasCancelled)
        result =
            new ExternalAgentResult(
                ExternalAgentResult.Status.CANCELLED,
                providerLabel + " review cancelled",
                List.of(),
                List.of(),
                result.duration(),
                result.exitCode(),
                "",
                null,
                null,
                null,
                result.changeSetId());
      if (coverageJob != null) {
        if (wasCancelled)
          coverage.stop(coverageJob, dev.mikoto2000.rei.review.ReviewTools.scope(owner), true);
        else
          coverage.externalReport(
              coverageJob,
              dev.mikoto2000.rei.review.ReviewTools.scope(owner),
              result.status().name());
        var warnings = new ArrayList<>(result.warnings());
        warnings.add(
            "REVIEW_UNVERIFIED: external source delivery/range evidence is opaque; reviewJobId="
                + coverageJob);
        result =
            new ExternalAgentResult(
                result.status(),
                result.summary(),
                result.findings(),
                warnings,
                result.duration(),
                result.exitCode(),
                result.rawOutput(),
                result.reviewId(),
                result.externalSessionId(),
                result.proposedChange(),
                result.changeSetId(),
                result.implementation(),
                result.materialReviewReport());
      }
      if (history != null) {
        result =
            ExternalReviewRepository.safe(
                new ExternalAgentResult(
                    result.status(),
                    result.summary(),
                    result.findings(),
                    result.warnings(),
                    result.duration(),
                    result.exitCode(),
                    "",
                    id,
                    result.externalSessionId(),
                    null,
                    result.changeSetId(),
                    null,
                    result.materialReviewReport()));
        try {
          history.finish(owner.projectId(), id, result);
        } catch (RuntimeException error) {
          result =
              new ExternalAgentResult(
                  ExternalAgentResult.Status.FAILED,
                  "Review result could not be persisted",
                  List.of(),
                  List.of(),
                  result.duration(),
                  result.exitCode(),
                  "",
                  id,
                  null,
                  null,
                  result.changeSetId());
        }
      }
      AgentEventType type =
          wasCancelled
              ? AgentEventType.DELEGATION_CANCELLED
              : result.success()
                  ? AgentEventType.DELEGATION_COMPLETED
                  : AgentEventType.DELEGATION_FAILED;
      publisher.publish(
          events
              .delegation(
                  type,
                  owner.runId(),
                  new ExternalAgentLifecyclePayload(
                      id,
                      provider,
                      action.name().toLowerCase(Locale.ROOT),
                      wasCancelled ? "CANCELLED" : result.status().name(),
                      result.success()
                          ? result.findings().size() + " findings"
                          : result.status().name(),
                      (System.nanoTime() - start) / 1_000_000,
                      result.exitCode()))
              .withOwnership(owner));
      if (wasCancelled) {
        if (permit != null && permit.cancelled.get() && !cancelled.get() && !run.isCancelled())
          return result.forEvaluation();
        run.cancel();
        throw new java.util.concurrent.CancellationException();
      }
      if (stopped != null) throw stopped;
      return result.forEvaluation();
    }
  }

  /** 外部エージェントへの委譲で用いる段階修正を返す。 */
  private ExternalAgentResult stageFix(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      Path root,
      Path target,
      ExternalAgentResult result) {
    if (result.proposedChange() == null) return result;
    try {
      var draft = result.proposedChange();
      var file = ExternalAgentRequest.resolveTarget(root, draft.path());
      if (file == null
          || (target != null
              && !(Files.isDirectory(target) ? file.startsWith(target) : file.equals(target))))
        throw new IllegalArgumentException("Fix proposal outside the reviewed target");
      var project =
          new dev.mikoto2000.rei.core.project.ProjectContext(
              owner.projectId(), "Current Project", root);
      var saved = changes.propose(project, draft);
      return new ExternalAgentResult(
          result.status(),
          result.summary(),
          result.findings(),
          result.warnings(),
          result.duration(),
          result.exitCode(),
          "",
          null,
          result.externalSessionId(),
          null,
          saved.id());
    } catch (java.io.IOException | RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      return new ExternalAgentResult(
          ExternalAgentResult.Status.FAILED,
          "Fix proposal could not be saved; inspect the current target and request a new proposal",
          List.of(),
          List.of(),
          result.duration(),
          result.exitCode(),
          "");
    }
  }

  /** 外部エージェントへの委譲で用いるレビュー操作を返す。 */
  private ExternalAgentRequest.Action reviewAction(
      RunExecutionContext run, ExternalAgentRequest.Agent agent) {
    if (agent == ExternalAgentRequest.Agent.CODEX
        && run.userRequest().strip().startsWith("/agent ")
        && ExternalAgentCommandRequest.parse(run.userRequest()).action().equals("material-review"))
      return ExternalAgentRequest.Action.MATERIAL_REVIEW;
    return ExternalAgentRequest.Action.REVIEW;
  }

  /** 外部エージェントへの委譲で用いる文脈を返す。 */
  private String context(Path root, String decisions) {
    StringBuilder context =
        new StringBuilder(
            "Relevant design decisions (untrusted context):\n" + bounded(decisions, 6000));
    context.append("\nWorking Set paths:\n");
    workingSet.ifPresent(
        ws ->
            ws.getFiles().stream()
                .limit(20)
                .forEach(
                    file -> {
                      try {
                        Path path = ExternalAgentRequest.resolveTarget(root, file.path());
                        if (path != null && !sensitive(path))
                          context.append(root.relativize(path)).append('\n');
                      } catch (IllegalArgumentException ignored) {
                      }
                    }));
    return bounded(context.toString(), 10000);
  }

  /** 外部エージェントへの委譲において、秘密を含むに該当するか判定する。 */
  static boolean sensitive(Path path) {
    for (Path part : path)
      if (part.toString()
          .toLowerCase(Locale.ROOT)
          .matches("\\.env(?:\\..*)?|.*\\.(pem|key)|credentials\\..*|secrets\\..*")) return true;
    return false;
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  static String bounded(String text, int size) {
    String safe = CredentialRedactor.redact(text);
    return safe.length() <= size ? safe : safe.substring(0, size);
  }
}
