package dev.mikoto2000.rei.reflection;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import dev.mikoto2000.rei.goal.GoalRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 目標振り返りのユースケースを実現する。
 *
 * <p>Reflects on saved verification facts, not model statements or inferred failure causes.
 */
@Service
public class GoalReflectionService {
  /** 停止済み。 */
  private static final Set<String> STOPPED =
      Set.of("COMPLETED", "BLOCKED", "FAILED", "PAUSED", "CANCELLED", "WAITING_APPROVAL");

  /** 目標一覧。 */
  private final GoalRepository goals;

  /** 振り返り一覧。 */
  private final GoalReflectionRepository reflections;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 購読。 */
  private AgentEventBus.Subscription subscription;

  /** 指定された値や利用先を使って、GoalReflectionServiceを初期化する。 */
  public GoalReflectionService(
      GoalRepository goals, GoalReflectionRepository reflections, AgentEventBus bus, Clock clock) {
    this.goals = goals;
    this.reflections = reflections;
    this.bus = bus;
    this.clock = clock;
  }

  /** 要求された処理を開始する。 */
  @PostConstruct
  public synchronized void start() {
    if (subscription == null) subscription = bus.subscribe(this::observe);
  }

  /** 作業の振り返りで利用した資源や登録を解放し、利用を終了する。 */
  @PreDestroy
  public synchronized void close() {
    if (subscription != null) {
      subscription.unsubscribe();
      subscription = null;
    }
  }

  /** 状況を判断する根拠を得るため、目標振り返りサービスを観測する。 */
  private void observe(AgentEvent source) {
    if (source.type() != AgentEventType.GOAL_UPDATED
        || source.projectId() == null
        || !(source.payload() instanceof GoalLifecyclePayload snapshot)
        || !STOPPED.contains(snapshot.status())) return;
    reflect(source, snapshot);
  }

  /**
   * 判断に必要な情報を集め、後続の処理へ渡す。
   *
   * <p>Human backfill after missed delivery/restart; this does not re-run or re-verify a Goal.
   */
  public GoalReflectionRepository.Item collect(String project, String id) {
    var goal = goals.get(project, id);
    if (!STOPPED.contains(goal.status()))
      throw new IllegalStateException("Only stopped or completed Goals can be reflected on");
    var snapshot =
        new GoalLifecyclePayload(
            goal.id(),
            goal.status(),
            goal.attempts(),
            goal.maxRuns(),
            goal.llmCallsUsed(),
            goal.maxLlmCalls(),
            goal.reason());
    return reflect(
        new AgentEvent(
            "manual-collect:" + UUID.randomUUID(),
            0,
            clock.instant(),
            AgentEventType.GOAL_UPDATED,
            1,
            goal.sessionId(),
            null,
            goal.currentRunId(),
            goal.id(),
            null,
            snapshot,
            goal.projectId()),
        snapshot);
  }

  /** 作業の振り返りで用いるreflectを返す。 */
  private GoalReflectionRepository.Item reflect(AgentEvent source, GoalLifecyclePayload snapshot) {
    var goal = goals.get(source.projectId(), snapshot.goalId());
    var attempt =
        goals.attempts(goal.projectId(), goal.id()).stream()
            .filter(
                a -> Objects.equals(a.runId(), source.runId()) && a.number() == snapshot.attempts())
            .findFirst();
    String actual = "NOT_CHECKED", reason = "not_recorded";
    if (snapshot.status().equals("COMPLETED")
        && Set.of("file_digest_verified", "criteria_verified", "completion_gate_verified")
            .contains(snapshot.reason())) {
      actual = "VERIFIED";
      reason = snapshot.reason();
    } else if (attempt.isPresent()) {
      reason = attempt.get().reason();
      if (Set.of(
              "digest_mismatch",
              "json_value_mismatch",
              "predicate_mismatch",
              "file_missing_or_not_regular",
              "completion_evidence_missing",
              "completion_required_tests_missing",
              "completion_required_artifact_missing",
              "completion_review_stale",
              "completion_test_evidence_changed")
          .contains(reason)) actual = "UNVERIFIED";
      else if (Set.of(
              "symbolic_link_rejected",
              "outside_project",
              "file_too_large",
              "project_path_changed",
              "json_file_too_large",
              "json_file_invalid")
          .contains(reason)) actual = "REJECTED";
    }
    String gap =
        switch (actual) {
          case "VERIFIED" -> "CRITERION_SATISFIED";
          case "UNVERIFIED" -> "CRITERION_NOT_SATISFIED";
          case "REJECTED" -> "VERIFICATION_REJECTED";
          default -> "VERIFICATION_NOT_RECORDED";
        };
    String next =
        switch (snapshot.status()) {
          case "COMPLETED" -> "REVIEW_VERIFIED_RESULT";
          case "WAITING_APPROVAL" -> "REVIEW_APPROVAL";
          case "FAILED" -> "INSPECT_EXECUTION_FAILURE";
          case "PAUSED", "CANCELLED" -> "RECONCILE_STOPPED_RUN";
          default ->
              snapshot.reason().equals("budget_exhausted")
                      || snapshot.attempts() >= snapshot.maxRuns()
                      || snapshot.llmCallsUsed() >= snapshot.maxLlmCalls()
                  ? "REVIEW_GOAL_BUDGET"
                  : actual.equals("REJECTED") ? "INSPECT_VERIFICATION" : "REVIEW_BLOCKER";
        };
    return reflections.save(goal, source, snapshot, actual, reason, gap, next);
  }
}
