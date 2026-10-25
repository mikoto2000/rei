package dev.mikoto2000.rei.temporal;

import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.chat.UserInterventionQueue;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 時刻と期間の解釈に必要なエージェント実行予定実行先の振り分けを扱う。
 *
 * <p>Admission shares the existing project FIFO; the durable claim precedes all external execution.
 */
@Component
@EnableConfigurationProperties(AgentSchedulerProperties.class)
public class AgentScheduleDispatcher {
  private java.util.List<dev.mikoto2000.rei.secretary.SecretaryTick> secretaries =
      java.util.List.of();

  public void setSecretary(dev.mikoto2000.rei.secretary.SecretaryTick secretary) {
    this.secretaries = java.util.List.of(secretary);
  }

  @Autowired(required = false)
  public void setSecretaries(
      java.util.List<dev.mikoto2000.rei.secretary.SecretaryTick> secretaries) {
    this.secretaries = java.util.List.copyOf(secretaries);
  }

  /** 状態表示Resumes。 */
  private dev.mikoto2000.rei.dot.DotResumeService dotResumes;

  /** 時刻と期間の解釈に関する設定状態表示Resumesを処理する。 */
  @Autowired(required = false)
  public void configureDotResumes(dev.mikoto2000.rei.dot.DotResumeService service) {
    dotResumes = service;
  }

  /** 継続入力一覧。 */
  private RunContinuation continuations;

  /** 時刻と期間の解釈で使用する継続入力一覧を設定する。 */
  @Autowired(required = false)
  public void setContinuations(RunContinuation continuations) {
    this.continuations = continuations;
  }

  /** 時刻と期間の解釈に必要な受付窓口を扱う。 */
  @FunctionalInterface
  interface Gateway {
    /** 時刻と期間の解釈に関する実行先への引き渡しを処理する。 */
    void dispatch(PersistentAgentScheduler.Entry entry, Consumer<ChatExecutionResult> completed);
  }

  /** 予約されたエージェント実行を管理する窓口。 */
  private final PersistentAgentScheduler schedules;

  /** この機能の動作設定。 */
  private final AgentSchedulerProperties properties;

  /** 権限一覧。 */
  private final ToolPermissionProperties permissions;

  /** 受付窓口。 */
  private final Gateway gateway;

  /** 入力先Flight。 */
  private final java.util.function.Predicate<PersistentAgentScheduler.Entry> inFlight;

  /** 現在利用権の確保。 */
  private final java.util.concurrent.atomic.AtomicReference<PersistentAgentScheduler.Entry>
      currentClaim = new java.util.concurrent.atomic.AtomicReference<>();

  /** 使用中。 */
  private final AtomicBoolean busy = new AtomicBoolean();

  /** 目標待機一覧。 */
  private dev.mikoto2000.rei.goal.GoalWaitService goalWaits;

  /** 時刻と期間の解釈に関する設定目標待機一覧を処理する。 */
  @Autowired(required = false)
  public void configureGoalWaits(dev.mikoto2000.rei.goal.GoalWaitService service) {
    goalWaits = service;
  }

  /** 実行登録台帳。 */
  private dev.mikoto2000.rei.application.run.RunRegistry runRegistry;

  /** 実行受付から終了までの管理。 */
  private dev.mikoto2000.rei.application.run.RunService runLifecycle;

  /** 時刻と期間の解釈に関する設定実行Trackingを処理する。 */
  @Autowired(required = false)
  public void configureRunTracking(
      dev.mikoto2000.rei.application.run.RunRegistry registry,
      dev.mikoto2000.rei.application.run.RunService lifecycle) {
    this.runRegistry = registry;
    this.runLifecycle = lifecycle;
  }

  /** 指定された値や利用先を使って、AgentScheduleDispatcherを初期化する。 */
  @Autowired
  public AgentScheduleDispatcher(
      PersistentAgentScheduler schedules,
      AgentSchedulerProperties properties,
      ToolPermissionProperties permissions,
      ProjectService projects,
      SessionRepository sessions,
      ConversationInputRouter router,
      ChatExecutionService chat) {
    this.schedules = schedules;
    this.properties = properties;
    this.permissions = permissions;
    this.inFlight = entry -> router.containsRun(entry.projectId(), entry.runId());
    this.gateway =
        (entry, completed) -> {
          var root = Path.of(entry.projectRoot());
          if (!validProject(entry, projects))
            throw new IllegalStateException(
                "Scheduled project is missing or relocated; create a new schedule");
          var session =
              sessions
                  .findById(entry.task().conversationId())
                  .orElseThrow(
                      () -> new IllegalStateException("Scheduled session no longer exists"));
          if (!session.projectId().equals(entry.projectId()))
            throw new IllegalStateException("Scheduled session belongs to a different project");
          var owner =
              new AgentRunContext(
                  entry.runId(),
                  session.sessionId(),
                  root,
                  entry.projectId(),
                  AgentRunContext.RequestSource.WEB);
          boolean registered = false;
          try {
            if (runRegistry != null) {
              runRegistry.register(owner);
              registered = true;
              runLifecycle.onQueuedCancellation(
                  entry.runId(), () -> completed.accept(ChatExecutionResult.cancelled()));
            }
            router.submitOperation(
                owner,
                () -> {
                  ChatExecutionResult result;
                  try {
                    if (!schedules.activeClaim(entry)) result = ChatExecutionResult.cancelled();
                    else {
                      if (!validProject(entry, projects))
                        throw new IllegalStateException(
                            "Scheduled project was relocated while queued");
                      var current =
                          sessions
                              .findById(owner.conversationId())
                              .orElseThrow(
                                  () ->
                                      new IllegalStateException(
                                          "Scheduled session no longer exists"));
                      if (!current.projectId().equals(owner.projectId()))
                        throw new IllegalStateException("Scheduled session ownership changed");
                      if (continuations != null) continuations.attachParent(owner, null, completed);
                      result =
                          chat.execute(owner, entry.task().action(), new UserInterventionQueue());
                    }
                  } catch (RuntimeException error) {
                    result = ChatExecutionResult.failed(error.getClass().getSimpleName());
                  }
                  if (result.status() == ChatExecutionResult.Status.WAITING) return;
                  if (continuations != null) continuations.detachParent(owner);
                  if (runLifecycle != null) {
                    var terminal =
                        result.status() == ChatExecutionResult.Status.CANCELLED
                            ? dev.mikoto2000.rei.application.run.RunStatus.CANCELLED
                            : result.success()
                                ? dev.mikoto2000.rei.application.run.RunStatus.COMPLETED
                                : dev.mikoto2000.rei.application.run.RunStatus.FAILED;
                    runLifecycle.finishMissingTerminal(owner, terminal);
                    if (runRegistry.get(entry.runId()).status()
                        == dev.mikoto2000.rei.application.run.RunStatus.CANCELLED)
                      result = ChatExecutionResult.cancelled();
                  }
                  completed.accept(result);
                },
                work -> {
                  Runnable execute =
                      () -> {
                        try {
                          work.run();
                        } catch (RuntimeException error) {
                          completed.accept(
                              ChatExecutionResult.failed(error.getClass().getSimpleName()));
                        }
                      };
                  if (runLifecycle != null) runLifecycle.execute(owner, execute);
                  else execute.run();
                });
          } catch (RuntimeException | Error error) {
            if (registered) {
              runLifecycle.forgetQueuedCancellation(entry.runId());
              runRegistry.forget(entry.runId());
            }
            throw error;
          }
        };
  }

  /** 時刻と期間の解釈を利用するため、指定を補って共通の初期化へ引き渡す。 */
  AgentScheduleDispatcher(
      PersistentAgentScheduler schedules,
      AgentSchedulerProperties properties,
      ToolPermissionProperties permissions,
      Gateway gateway) {
    this(schedules, properties, permissions, gateway, entry -> true);
  }

  /** 指定された値や利用先を使って、AgentScheduleDispatcherを初期化する。 */
  AgentScheduleDispatcher(
      PersistentAgentScheduler schedules,
      AgentSchedulerProperties properties,
      ToolPermissionProperties permissions,
      Gateway gateway,
      java.util.function.Predicate<PersistentAgentScheduler.Entry> inFlight) {
    this.schedules = schedules;
    this.properties = properties;
    this.permissions = permissions;
    this.gateway = gateway;
    this.inFlight = inFlight;
  }

  /** 時刻と期間の解釈において、有効プロジェクトに該当するか判定する。 */
  private static boolean validProject(
      PersistentAgentScheduler.Entry entry, ProjectService projects) {
    var root = Path.of(entry.projectRoot());
    try {
      return Files.isDirectory(root)
          && root.toRealPath().equals(root)
          && projects.registeredProjects().stream()
              .anyMatch(p -> p.id().equals(entry.projectId()) && p.root().equals(root));
    } catch (java.io.IOException error) {
      return false;
    }
  }

  /** 時刻と期間の解釈に関する定期確認を処理する。 */
  @Scheduled(fixedDelayString = "${rei.agent-scheduler.check-interval-ms:5000}")
  public synchronized void tick() {
    for (var secretary : secretaries) {
      try {
        secretary.tick();
      } catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Secretary clock observer failed: {}", failure.getClass().getSimpleName());
      }
    }
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!properties.enabled() || !permissions.enabled() || !busy.compareAndSet(false, true)) return;
    PersistentAgentScheduler.Entry claim = null;
    try {
      var next = schedules.claimDue();
      if (next.isEmpty()) {
        busy.set(false);
        return;
      }
      claim = next.get();
      currentClaim.set(claim);
      var owned = claim;
      Consumer<ChatExecutionResult> completed =
          result -> {
            if (result.status() == ChatExecutionResult.Status.WAITING) return;
            try {
              if (schedules.activeClaim(owned))
                schedules.finish(
                    owned,
                    switch (result.status()) {
                      case SUCCESS -> "COMPLETED";
                      case FAILED -> "FAILED";
                      case CANCELLED -> "CANCELLED";
                      case WAITING ->
                          throw new IllegalStateException("Waiting is not a schedule completion");
                    },
                    result.success() ? result.text() : result.errorMessage());
            } finally {
              release(owned);
            }
          };
      if (owned.task().action().startsWith("dot-resume:")) {
        if (dotResumes == null)
          throw new IllegalStateException("Dot continuation service unavailable");
        completed.accept(dotResumes.dispatch(owned));
      } else if (goalWaits != null && goalWaits.handles(owned))
        goalWaits.dispatch(owned, completed);
      else gateway.dispatch(owned, completed);
    } catch (RuntimeException error) {
      try {
        if (claim != null && schedules.activeClaim(claim))
          schedules.finish(claim, "FAILED", error.getMessage());
      } finally {
        if (claim == null) busy.set(false);
        else release(claim);
      }
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Schedule dispatch failed: {}", error.getClass().getSimpleName());
    }
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  public synchronized PersistentAgentScheduler.Entry reconcile(
      String project, String id, String expectedRunId) {
    var entry = schedules.get(project, id);
    if (inFlight.test(entry))
      throw new IllegalStateException(
          "Schedule Run is queued or executing; stop it through normal Run controls");
    var result = schedules.reconcile(project, id, expectedRunId);
    var owned = currentClaim.get();
    if (owned != null
        && owned.task().id().equals(id)
        && owned.projectId().equals(project)
        && java.util.Objects.equals(owned.runId(), expectedRunId)) release(owned);
    return result;
  }

  /** 後続の処理が利用できるよう、エージェント実行予定実行先の振り分けを解放する。 */
  private synchronized void release(PersistentAgentScheduler.Entry owned) {
    if (currentClaim.compareAndSet(owned, null)) busy.set(false);
  }
}
