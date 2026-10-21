package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunSnapshot;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.temporal.AgentSchedulerProperties;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 状態表示再開のユースケースを実現する。
 *
 * <p>Human review and a durable intent feed the existing scheduler/checkpoint router.
 */
@Service
public final class DotResumeService {
  /** 依存条件一覧。 */
  private final dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies;

  /** リポジトリ。 */
  private final DotRequestRepository repo;

  /** 委譲。 */
  private final DotDelegationService delegation;

  /** 再開用の記録一覧。 */
  private final PersistentCheckpointRepository checkpoints;

  /** 再開用の記録サービス。 */
  private final PersistentCheckpointService checkpointService;

  /** 予約されたエージェント実行を管理する窓口。 */
  private final PersistentAgentScheduler schedules;

  /** 実行一覧。 */
  private final org.springframework.beans.factory.ObjectProvider<RunRegistry> runs;

  /** 会話一覧。 */
  private final SessionRepository sessions;

  /** 実行予定の管理設定。 */
  private final AgentSchedulerProperties schedulerSettings;

  /** 権限一覧。 */
  private final dev.mikoto2000.rei.core.policy.ToolPermissionProperties permissions;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、DotResumeServiceを初期化する。 */
  public DotResumeService(
      dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies,
      DotRequestRepository repo,
      DotDelegationService delegation,
      PersistentCheckpointRepository checkpoints,
      PersistentCheckpointService checkpointService,
      PersistentAgentScheduler schedules,
      org.springframework.beans.factory.ObjectProvider<RunRegistry> runs,
      SessionRepository sessions,
      AgentSchedulerProperties schedulerSettings,
      dev.mikoto2000.rei.core.policy.ToolPermissionProperties permissions,
      Clock clock) {
    this.dependencies = dependencies;
    this.repo = repo;
    this.delegation = delegation;
    this.checkpoints = checkpoints;
    this.checkpointService = checkpointService;
    this.schedules = schedules;
    this.runs = runs;
    this.sessions = sessions;
    this.schedulerSettings = schedulerSettings;
    this.permissions = permissions;
    this.clock = clock;
  }

  /** 要求された条件を満たすことを確認する。 */
  public Map<String, Object> verify(String project, String id, Map<String, Object> input) {
    delegation.project(project);
    DotPayload.keys(input, Set.of("confirmed", "status", "note", "satisfied"));
    confirmed(input);
    String status = DotPayload.text(input, "status", true, 40),
        note = DotPayload.text(input, "note", true, 4096);
    var indices = new ArrayList<Integer>();
    Object raw = input.getOrDefault("satisfied", List.of());
    if (raw instanceof String text) {
      if (!text.isBlank())
        for (String token : text.split(",")) {
          try {
            indices.add(Integer.valueOf(token.strip()));
          } catch (NumberFormatException e) {
            throw new DotException("INVALID_ARGUMENT");
          }
        }
    } else if (raw instanceof List<?> list) {
      for (Object n : list) indices.add(DotPayload.integer(n, -1, 0, 15));
    } else throw new DotException("INVALID_ARGUMENT");
    var result = repo.review(project, id, status, indices, note);
    var owner = repo.owner(project, id);
    if (owner.run() != null) {
      var dependency = ownedDependency(owner);
      var state =
          switch (status) {
            case "VERIFIED" -> dev.mikoto2000.rei.core.dependency.DependencyState.COMPLETED;
            case "REJECTED", "FAILED" -> dev.mikoto2000.rei.core.dependency.DependencyState.FAILED;
            default -> dev.mikoto2000.rei.core.dependency.DependencyState.WAITING;
          };
      dependencies.observe(dependency, state, "dot_" + status.toLowerCase(Locale.ROOT));
      repo.audit(project, id, "human", "REVIEW_PARENT", "MANUAL_REVIEW");
    }
    return result;
  }

  /** 利用者の状態表示に関する確認済みを処理する。 */
  private static void confirmed(Map<String, Object> input) {
    if (!Boolean.TRUE.equals(input.get("confirmed")))
      throw new DotException("HUMAN_CONFIRMATION_REQUIRED");
  }

  /** 利用者の状態表示で用いる自分が所有する対象依存条件を返す。 */
  private dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository.Entry ownedDependency(
      DotRequestRepository.Owner owner) {
    var dependency = dependencies.get(owner.project(), owner.dependency());
    if (!dependency.projectRoot().equals(owner.root())
        || !dependency.sessionId().equals(owner.session())
        || !Objects.equals(dependencies.creatorRun(owner.project(), dependency.id()), owner.run())
        || dependency.spec().kind()
            != dev.mikoto2000.rei.core.dependency.DependencySpec.Kind.DOT_REQUEST
        || !dependency.spec().target().equals(owner.id()))
      throw new DotException("DEPENDENCY_OWNER_CHANGED");
    return dependency;
  }

  /** 利用者の状態表示で用いる確認済みを返す。 */
  private PersistentCheckpoint checked(DotRequestRepository.Owner owner) {
    var project = delegation.project(owner.project());
    if (!project.root().toString().equals(owner.root()) || !Files.isDirectory(project.root()))
      throw new DotException("OWNER_CHANGED");
    try {
      if (!project.root().toRealPath().equals(project.root()))
        throw new DotException("OWNER_CHANGED");
    } catch (java.io.IOException e) {
      throw new DotException("OWNER_CHANGED");
    }
    if (owner.run() == null
        || owner.task() == null
        || owner.session() == null
        || owner.dependency() == null) throw new DotException("NO_PARENT_RUN");
    sessions
        .findById(owner.session())
        .filter(s -> s.projectId().equals(owner.project()))
        .orElseThrow(() -> new DotException("SESSION_NOT_FOUND"));
    var registry = runs.getIfAvailable();
    if (registry == null) throw new DotException("RUN_TRACKING_REQUIRED");
    RunSnapshot previous;
    try {
      previous = registry.get(owner.run());
    } catch (RuntimeException e) {
      throw new DotException("PARENT_NOT_WAITING");
    }
    if (previous.status() != RunStatus.WAITING
        || !previous.context().projectId().equals(owner.project())
        || !previous.context().conversationId().equals(owner.session())
        || !previous.context().projectRoot().toString().equals(owner.root()))
      throw new DotException("PARENT_NOT_WAITING");
    if (ownedDependency(owner).state()
        != dev.mikoto2000.rei.core.dependency.DependencyState.COMPLETED)
      throw new DotException("DEPENDENCY_NOT_VERIFIED");
    var state = checkpoints.get(owner.project(), owner.task());
    if (!state.runId().equals(owner.run())
        || !state.sessionId().equals(owner.session())
        || !state.projectRoot().equals(owner.root())
        || !state.status().equals("WAITING")
        || state.mode() != AgentRunContext.Mode.EXCLUSIVE)
      throw new DotException("CHECKPOINT_OWNER_CHANGED");
    if (!checkpointService.inspect(owner.project(), owner.task()).automaticResumeSafe())
      throw new DotException("RECONCILIATION_REQUIRED");
    return state;
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  public Map<String, Object> resume(String project, String id, Map<String, Object> input) {
    delegation.project(project);
    DotPayload.keys(input, Set.of("confirmed"));
    confirmed(input);
    if (!schedulerSettings.enabled() || !permissions.enabled())
      throw new DotException("SCHEDULER_PERMISSION_REQUIRED");
    var owner = repo.owner(project, id);
    var existing = repo.intent(project, id);
    if (!"NOT_REQUESTED".equals(existing.get("status"))) return status(project, id);
    var state = checked(owner);
    repo.result(project, id);
    if (!repo.requestResume(project, id, state.revision())) return status(project, id);
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext(owner.run(), owner.session(), Path.of(owner.root()), project))) {
      var task =
          schedules.scheduleAt(clock.instant().plusSeconds(1), "dot-resume:" + id, owner.session());
      repo.attachSchedule(project, id, task.id());
      schedules.activate(project, task.id());
    } catch (RuntimeException failure) {
      repo.finishResume(project, id, "UNKNOWN");
      throw new DotException("RESUME_ADMISSION_UNKNOWN");
    }
    return status(project, id);
  }

  /** 利用者の状態表示において、操作対象一覧に該当するか判定する。 */
  public boolean handles(PersistentAgentScheduler.Entry entry) {
    return entry.task().action().startsWith("dot-resume:");
  }

  /** 利用者の状態表示で用いる実行先への引き渡しを返す。 */
  public ChatExecutionResult dispatch(PersistentAgentScheduler.Entry entry) {
    try {
      return dispatchChecked(entry);
    } catch (RuntimeException failure) {
      String id = entry.task().action().substring("dot-resume:".length());
      try {
        repo.finishResume(
            entry.projectId(), id, failure instanceof DotException ? "FAILED" : "UNKNOWN");
      } catch (RuntimeException ignored) {
      }
      throw failure;
    }
  }

  /** 利用者の状態表示で用いる実行先への引き渡し確認済みを返す。 */
  private ChatExecutionResult dispatchChecked(PersistentAgentScheduler.Entry entry) {
    String id = entry.task().action().substring("dot-resume:".length());
    var owner = repo.owner(entry.projectId(), id);
    var intent = repo.intent(entry.projectId(), id);
    if (!Objects.equals(intent.get("schedule"), entry.task().id())
        || !owner.root().equals(entry.projectRoot())
        || !Objects.equals(owner.session(), entry.task().conversationId())
        || !schedules.activeClaim(entry)) throw new DotException("RESUME_OWNER_CHANGED");
    var state = checked(owner);
    if (((Number) intent.get("revision")).longValue() != state.revision())
      throw new DotException("CHECKPOINT_REVISION_CHANGED");
    var result = repo.result(owner.project(), id);
    if (!repo.startResume(owner.project(), id, entry.task().id(), entry.runId()))
      throw new DotException("RESUME_ALREADY_STARTED");
    try {
      var resumed =
          checkpointService.resumeForDot(
              owner.project(),
              owner.task(),
              state.revision(),
              entry.runId(),
              owner.run(),
              "Untrusted external result (manual acceptance recorded): "
                  + DotPayload.safe(Objects.toString(result.get("summary"), ""))
                  + "; request_id="
                  + id);
      if (!resumed.runId().equals(entry.runId())) throw new DotException("RESUME_OWNER_CHANGED");
      repo.finishResume(owner.project(), id, "ADMITTED");
      return ChatExecutionResult.success(
          "Checkpoint continuation admitted; completion is tracked separately", false);
    } catch (RuntimeException failure) {
      repo.finishResume(owner.project(), id, "UNKNOWN");
      throw failure;
    }
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public Map<String, Object> status(String project, String id) {
    var out = new LinkedHashMap<String, Object>(delegation.get(project, id));
    try {
      var intent = repo.intent(project, id);
      out.put("resume_status", intent.get("status"));
      if (intent.get("run") instanceof String run) {
        out.put("resumed_run_id", run);
        var registry = runs.getIfAvailable();
        out.put(
            "resumed_run_status", registry == null ? "UNKNOWN" : registry.get(run).status().name());
      }
    } catch (DotException ignored) {
    } catch (IllegalArgumentException missing) {
      out.put("resumed_run_status", "UNKNOWN");
    }
    return out;
  }

  /** 処理を継続できる状態に戻すため、状態表示再開サービスを復旧する。 */
  @jakarta.annotation.PostConstruct
  void recover() {
    repo.recoverResumeIntents();
  }
}
