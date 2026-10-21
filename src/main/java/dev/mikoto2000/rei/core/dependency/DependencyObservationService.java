package dev.mikoto2000.rei.core.dependency;

import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.DependencyStatusPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 依存条件観測内容のユースケースを実現する。
 *
 * <p>Bounded observation and durable at-least-once facts. No action execution or automatic replays.
 */
@Component
@EnableConfigurationProperties(DependencyWatcherProperties.class)
public class DependencyObservationService {
  /** リポジトリ。 */
  private final PersistentDependencyRepository repo;

  /** 利用可否の確認。 */
  private final DependencyProbe probe;

  /** この機能の動作設定。 */
  private final DependencyWatcherProperties properties;

  /** 判断基準。 */
  private final ToolPermissionPolicy policy;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 会話一覧。 */
  private final SessionRepository sessions;

  /** 変更後。 */
  private String after = "";

  /** 外部条件の待機を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DependencyObservationService(
      PersistentDependencyRepository repo,
      DependencyProbe probe,
      DependencyWatcherProperties properties,
      ToolPermissionPolicy policy,
      AgentEventPublisher publisher) {
    this(repo, probe, properties, policy, publisher, null, null);
  }

  /** 指定された値や利用先を使って、DependencyObservationServiceを初期化する。 */
  @Autowired
  public DependencyObservationService(
      PersistentDependencyRepository repo,
      DependencyProbe probe,
      DependencyWatcherProperties properties,
      ToolPermissionPolicy policy,
      AgentEventPublisher publisher,
      ProjectService projects,
      SessionRepository sessions) {
    this.repo = repo;
    this.probe = probe;
    this.properties = properties;
    this.policy = policy;
    this.publisher = publisher;
    this.projects = projects;
    this.sessions = sessions;
  }

  /** 対象の状態や内容を調べ、呼び出し元が確認できる情報として返す。 */
  public PersistentDependencyRepository.Entry inspect(String project, String id, boolean network) {
    var entry = repo.prepare(project, id);
    if (entry.spec().network() && !network)
      throw new IllegalArgumentException("HTTP observation requires the network tool/control");
    if (PersistentDependencyRepository.terminal(entry.state())
        || entry.reason().equals("dependency_waiting")
        || entry.reason().equals("dependency_failed")) {
      flushFacts();
      return entry;
    }
    if (!validProject(entry)) {
      entry = repo.observe(entry, DependencyState.BLOCKED, "owner_unavailable");
      flushFacts();
      return entry;
    }
    var observed = probe.probe(entry);
    if (!observed.id().equals(entry.id()))
      throw new IllegalStateException("Probe identity mismatch");
    var updated = repo.observe(entry, observed.state(), observed.detail());
    flushFacts();
    return updated;
  }

  /** 再開時刻の取得元。 */
  private java.time.Clock resumeClock = java.time.Clock.systemUTC();

  /** 外部条件の待機に関する設定再開時刻の取得元を処理する。 */
  @Autowired
  public void configureResumeClock(java.time.Clock clock) {
    resumeClock = clock;
  }

  /**
   * 外部条件の待機で用いる再確認対象再開を返す。
   *
   * <p>Fresh read-only observation, including terminal facts and all prerequisites. Never rewrites
   * history.
   */
  public DependencyObservation recheckForResume(String project, String id) {
    return recheck(project, id, new java.util.HashMap<>());
  }

  /** 外部条件の待機で用いる再確認を返す。 */
  private DependencyObservation recheck(
      String project, String id, java.util.Map<String, DependencyObservation> visited) {
    if (visited.containsKey(id)) return visited.get(id);
    if (visited.size() >= 64)
      return new DependencyObservation(id, DependencyState.BLOCKED, "dependency_recheck_limit");
    visited.put(
        id, new DependencyObservation(id, DependencyState.BLOCKED, "dependency_recheck_limit"));
    var entry = repo.get(project, id);
    if (entry.state() == DependencyState.CANCELLED || entry.state() == DependencyState.FAILED)
      return new DependencyObservation(id, entry.state(), entry.reason());
    if (!entry.deadline().isAfter(resumeClock.instant()))
      return new DependencyObservation(id, DependencyState.BLOCKED, "dependency_deadline_expired");
    if (!validProject(entry))
      return new DependencyObservation(id, DependencyState.BLOCKED, "owner_unavailable");
    if (!policy.enforced())
      return new DependencyObservation(id, DependencyState.BLOCKED, "permission_policy_disabled");
    var permission =
        policy.evaluate(entry.spec().network() ? "checkHttpDependency" : "checkDependency");
    if (permission != PermissionDecision.AUTO_APPROVE)
      return new DependencyObservation(
          id,
          DependencyState.BLOCKED,
          permission == PermissionDecision.DENY ? "permission_denied" : "permission_required");
    for (String parent : entry.prerequisites()) {
      var prerequisite = repo.get(project, parent);
      if (!entry.projectRoot().equals(prerequisite.projectRoot())
          || !entry.sessionId().equals(prerequisite.sessionId())
          || recheck(project, parent, visited).state() != DependencyState.COMPLETED)
        return new DependencyObservation(id, DependencyState.BLOCKED, "dependency_waiting");
    }
    var observed = probe.probe(entry);
    if (!observed.id().equals(id)) throw new IllegalStateException("Probe identity mismatch");
    visited.put(id, observed);
    return observed;
  }

  /** 外部条件の待機において、有効プロジェクトに該当するか判定する。 */
  private boolean validProject(PersistentDependencyRepository.Entry entry) {
    try {
      var root = Path.of(entry.projectRoot());
      return Files.isDirectory(root)
          && root.toRealPath().equals(root)
          && (projects == null
              || projects.registeredProjects().stream()
                  .anyMatch(p -> p.id().equals(entry.projectId()) && p.root().equals(root)))
          && (sessions == null
              || sessions
                  .findById(entry.sessionId())
                  .filter(session -> entry.projectId().equals(session.projectId()))
                  .isPresent());
    } catch (java.io.IOException | IllegalArgumentException error) {
      return false;
    }
  }

  /** 外部条件の待機に関する定期確認を処理する。 */
  @Scheduled(fixedDelayString = "${rei.dependency-watcher.check-interval-ms:5000}")
  public synchronized void tick() {
    flushFacts();
    if (!properties.enabled() || !policy.enforced()) return;
    var entries = repo.activeAfter(after);
    if (entries.isEmpty()) {
      after = "";
      entries = repo.activeAfter(after);
    }
    for (var saved : entries) {
      after = saved.id();
      try {
        var entry = repo.prepare(saved.projectId(), saved.id());
        if (PersistentDependencyRepository.terminal(entry.state())
            || entry.reason().equals("dependency_waiting")
            || entry.reason().equals("dependency_failed")) continue;
        boolean http = entry.spec().network();
        var permission = policy.evaluate(http ? "checkHttpDependency" : "checkDependency");
        if (permission != PermissionDecision.AUTO_APPROVE)
          repo.observe(
              entry,
              DependencyState.BLOCKED,
              permission == PermissionDecision.DENY ? "permission_denied" : "permission_required");
        else inspect(entry.projectId(), entry.id(), http);
      } catch (java.util.concurrent.CancellationException cancelled) {
        throw cancelled;
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Dependency observation failed: {}", error.getClass().getSimpleName());
      }
    }
    flushFacts();
  }

  /** 外部条件の待機に関する保存の確定事実一覧を処理する。 */
  public synchronized void flushFacts() {
    for (var fact : repo.pendingFacts()) {
      var type =
          switch (fact.state()) {
            case COMPLETED -> AgentEventType.DEPENDENCY_COMPLETED;
            case FAILED -> AgentEventType.DEPENDENCY_FAILED;
            case CANCELLED -> AgentEventType.DEPENDENCY_CANCELLED;
            default -> AgentEventType.DEPENDENCY_UPDATED;
          };
      var event =
          new AgentEvent(
              fact.eventId(),
              0,
              fact.timestamp(),
              type,
              1,
              fact.sessionId(),
              null,
              null,
              fact.dependencyId(),
              null,
              new DependencyStatusPayload(
                  fact.dependencyId(),
                  fact.kind(),
                  fact.state().name(),
                  fact.reason(),
                  fact.version()),
              fact.projectId());
      try {
        publisher.publishBoundary(event);
        repo.ackFact(fact.eventId());
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Dependency fact delivery failed: {}", error.getClass().getSimpleName());
        return;
      }
    }
  }
}
