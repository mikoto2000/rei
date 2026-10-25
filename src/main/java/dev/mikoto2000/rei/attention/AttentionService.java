package dev.mikoto2000.rei.attention;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.AttentionRequiredPayload;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.event.DependencyStatusPayload;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.temporal.MonotonicTimeSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 注意のユースケースを実現する。
 *
 * <p>Derives attention only from owned execution facts, without interpreting model text.
 */
@Component
public class AttentionService implements dev.mikoto2000.rei.secretary.SecretaryNotifications {
  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy value) {
    life = value;
  }

  @Override
  public void deliver(dev.mikoto2000.rei.secretary.SecretaryNotifications.Notice notice) {
    if (life != null && !life.notificationAllowed(notice.category().name(), notice.id()))
      throw new dev.mikoto2000.rei.life.LifeContext.NotificationDeferred();
    var saved = inbox.createSecretary(notice);
    publisher.publish(
        new AgentEvent(
            notice.id(),
            0,
            notice.created(),
            AgentEventType.ATTENTION_REQUIRED,
            1,
            null,
            null,
            null,
            saved.id(),
            null,
            new AttentionRequiredPayload(saved.id(), saved.kind(), saved.message()),
            notice.global() ? null : notice.projectId()));
  }

  /**
   * 注意の管理で受け渡す実行を表す。
   *
   * @param project プロジェクト
   * @param session 会話
   * @param run 実行
   */
  private record Run(String project, String session, String run) {}

  /** 受信した案内や確認待ち情報の保存先。 */
  private final AttentionRepository inbox;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 時刻。 */
  private final MonotonicTimeSource time;

  /** 待機中。 */
  private final Map<Run, Long> waiting = new LinkedHashMap<>();

  /** 完了済み。 */
  private final Set<Run> finished = new LinkedHashSet<>();

  /** 購読。 */
  private AgentEventBus.Subscription subscription;

  /** 承認一覧。 */
  private dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals;

  /** 注意の管理で使用する承認一覧を設定する。 */
  @Autowired(required = false)
  public void setApprovals(dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals) {
    this.approvals = approvals;
  }

  /** 指定された値や利用先を使って、AttentionServiceを初期化する。 */
  @Autowired
  public AttentionService(
      AttentionRepository inbox,
      AgentEventBus bus,
      AgentEventPublisher publisher,
      Clock clock,
      MonotonicTimeSource time) {
    this.inbox = inbox;
    this.bus = bus;
    this.publisher = publisher;
    this.clock = clock;
    this.time = time;
  }

  /** 要求された処理を開始する。 */
  @PostConstruct
  public synchronized void start() {
    if (subscription == null) subscription = bus.subscribe(this::observe);
  }

  /** 注意の管理で利用した資源や登録を解放し、利用を終了する。 */
  @PreDestroy
  public synchronized void close() {
    if (subscription != null) {
      subscription.unsubscribe();
      subscription = null;
    }
    waiting.clear();
    finished.clear();
  }

  /** 状況を判断する根拠を得るため、注意サービスを観測する。 */
  private synchronized void observe(AgentEvent event) {
    if (event.projectId() == null || event.sessionId() == null) return;
    if (event.type() == AgentEventType.DEPENDENCY_UPDATED
        || event.type() == AgentEventType.DEPENDENCY_COMPLETED
        || event.type() == AgentEventType.DEPENDENCY_FAILED
        || event.type() == AgentEventType.DEPENDENCY_CANCELLED) {
      dependency(event);
      return;
    }
    if (event.runId() == null) return;
    var run = new Run(event.projectId(), event.sessionId(), event.runId());
    switch (event.type()) {
      case GOAL_UPDATED -> {
        var payload = (GoalLifecyclePayload) event.payload();
        if (payload.status().equals("BLOCKED") || payload.status().equals("FAILED"))
          notify(
              event,
              "GOAL_STOPPED",
              payload.goalId(),
              "A Goal stopped before its criterion was verified. Inspect /goal show and /goal history before retrying.");
      }
      case TOOL_FAILED -> {
        var payload = (ToolFailedPayload) event.payload();
        if (payload.error() == null) return;
        switch (payload.error().errorType()) {
          case "PermissionRequired" -> approvalNotice(event, payload);
          case "PermissionDenied" ->
              notify(
                  event,
                  "POLICY_DENIED",
                  bounded(payload.toolName()),
                  "Policy denied a Tool action. Review the task and policy; acknowledging this item does not grant permission.");
          case null, default -> {}
        }
      }
      case STAGNATION_UPDATED -> {
        var payload = (ExecutionProgressPayload) event.payload();
        if (!"waiting_for_dependency".equals(payload.reason())) {
          waiting.remove(run);
          return;
        }
        if (finished.contains(run)) return;
        long now = time.nanoTime();
        long since = waiting.computeIfAbsent(run, ignored -> now);
        // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
        if (waiting.size() > 1024) waiting.remove(waiting.keySet().iterator().next());
        if (now - since >= Duration.ofMinutes(2).toNanos())
          notify(
              event,
              "LONG_WAIT",
              "dependency",
              "A dependency has remained waiting for at least two minutes. Inspect the process and task before intervening.");
      }
      case PROGRESS_DETECTED, STAGNATION_RECOVERED -> waiting.remove(run);
      case STAGNATION_STOPPED -> {
        waiting.remove(run);
        notify(
            event,
            "STAGNATION_STOPPED",
            "stagnation",
            "The run stopped after repeated stagnation. Inspect its result and checkpoint before resuming.");
      }
      case AGENT_RUN_COMPLETED, AGENT_RUN_FAILED, AGENT_RUN_CANCELLED -> {
        if (event.type() == AgentEventType.AGENT_RUN_COMPLETED
            && event.payload() instanceof AgentRunCompletedPayload payload
            && run.run().equals(payload.runId()))
          notify(
              event,
              "RUN_COMPLETED",
              "terminal",
              "The Agent Run ended normally. Inspect its result; declared Goal criteria still require independent verification.");
        else if (event.type() == AgentEventType.AGENT_RUN_FAILED
            && event.payload() instanceof AgentRunFailedPayload payload
            && run.run().equals(payload.runId()))
          notify(
              event,
              "RUN_FAILED",
              "terminal",
              "The Agent Run failed. Inspect its result and checkpoint before retrying; acknowledgement does not resume it.");
        waiting.remove(run);
        finished.add(run);
        if (finished.size() > 1024) finished.remove(finished.iterator().next());
      }
      default -> {}
    }
  }

  /** 注意の管理に関する承認通知を処理する。 */
  private void approvalNotice(AgentEvent event, ToolFailedPayload payload) {
    var match =
        java.util.regex.Pattern.compile("Approval required: ([0-9a-fA-F-]{36})")
            .matcher(Objects.toString(payload.error().message(), ""));
    if (approvals != null && match.find()) {
      try {
        var request = approvals.get(event.projectId(), match.group(1));
        if (!request.sessionId().equals(event.sessionId())
            || !request.runId().equals(event.runId()))
          throw new IllegalArgumentException("Approval notification owner mismatch");
        String message =
            "承認待ち: "
                + request.tool()
                + "\n"
                + request.argumentsPreview()
                + "\n有効期限: "
                + request.expiresAt().atZone(ZoneId.systemDefault())
                + "\n詳細: /approval show "
                + request.id()
                + "\nこの操作を承認して続行するには、次のコマンドを入力してください:\n/approval approve "
                + request.id()
                + " --continue\n拒否: /approval deny "
                + request.id();
        notify(event, "APPROVAL_REQUIRED", request.id(), message);
        return;
      } catch (RuntimeException unavailable) {
        /* Retain a generic notice when metadata is unavailable. */
      }
    }
    notify(
        event,
        "APPROVAL_REQUIRED",
        bounded(payload.toolName()),
        "Tool approval is required. Review /approval list, decide explicitly, then resume or retry.");
  }

  /** 注意の管理に関する依存条件を処理する。 */
  private void dependency(AgentEvent event) {
    if (!(event.payload() instanceof DependencyStatusPayload payload)
        || event.runId() != null
        || payload.revision() < 0
        || payload.dependencyId() == null
        || payload.dependencyId().isBlank()
        || payload.dependencyId().length() > 128
        || !payload.dependencyId().equals(event.correlationId())) return;
    String kind = null, message = null;
    if (event.type() == AgentEventType.DEPENDENCY_COMPLETED
        && "COMPLETED".equals(payload.state())) {
      kind = "DEPENDENCY_COMPLETED";
      message =
          "The declared dependency condition was observed. Inspect /dependency show and history before continuing the task.";
    } else if (event.type() == AgentEventType.DEPENDENCY_FAILED
        && "FAILED".equals(payload.state())) {
      kind = "DEPENDENCY_FAILED";
      message =
          "A dependency failed or its deadline expired. Inspect /dependency show and history before retrying.";
    } else if (event.type() == AgentEventType.DEPENDENCY_UPDATED
        && "USER_ANSWER".equals(payload.kind())
        && "WAITING".equals(payload.state())
        && ("user_answer_waiting".equals(payload.reason())
            || "watch_pending".equals(payload.reason()))) {
      kind = "DECISION_REQUIRED";
      message =
          "A task is waiting for a human answer. Review /dependency show and use /dependency answer explicitly; acknowledgement does not answer or grant consent.";
    }
    if (kind != null)
      publishAttention(
          event,
          inbox.createDependency(event, kind, payload.dependencyId(), message),
          kind,
          message);
  }

  /** 実行状況を利用先で把握できるよう、注意を通知する。 */
  private void publishAttention(
      AgentEvent source, Optional<AttentionRepository.Item> item, String kind, String message) {
    if (life != null && !life.notificationAllowed("NORMAL", source.id())) {
      item.ifPresent(saved -> inbox.hold(saved.projectId(), saved.id()));
      return;
    }
    item.ifPresent(
        saved ->
            publisher.publish(
                new AgentEvent(
                    UUID.randomUUID().toString(),
                    0,
                    clock.instant(),
                    AgentEventType.ATTENTION_REQUIRED,
                    1,
                    source.sessionId(),
                    source.turnId(),
                    source.runId(),
                    saved.id(),
                    source.id(),
                    new AttentionRequiredPayload(saved.id(), kind, message),
                    source.projectId())));
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${rei.attention.life-release-interval-ms:60000}")
  public synchronized void releaseHeld() {
    if (life == null || !life.notificationAllowed("NORMAL", "")) return;
    for (var project : inbox.heldProjects()) {
      var digest = inbox.releaseHeld(project);
      if (digest.isPresent()) {
        var saved = digest.get();
        if (!life.notificationAllowed("NORMAL", saved.id())) {
          inbox.hold(project, saved.id());
          return;
        }
        publisher.publish(
            new AgentEvent(
                saved.id(),
                0,
                clock.instant(),
                AgentEventType.ATTENTION_REQUIRED,
                1,
                saved.sessionId(),
                null,
                saved.runId(),
                saved.id(),
                null,
                new AttentionRequiredPayload(saved.id(), saved.kind(), saved.message()),
                project));
        return;
      }
    }
  }

  /** 状態の変化を利用先へ伝えるため、注意サービスを通知する。 */
  private void notify(AgentEvent source, String kind, String reference, String message) {
    publishAttention(source, inbox.create(source, kind, reference, message), kind, message);
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private static String bounded(String value) {
    String safe = CredentialRedactor.redact(value == null ? "unknown" : value);
    return safe.substring(0, Math.min(128, safe.length()));
  }
}
