package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.ActivityCapture;
import dev.mikoto2000.rei.activity.ActivityProperties;
import dev.mikoto2000.rei.activity.ActivityStore;
import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.topic.AgentMessage;
import dev.mikoto2000.rei.topic.AgentMessagePublisher;
import dev.mikoto2000.rei.topic.MessageOrigin;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 作業習慣のユースケースを実現する。
 *
 * <p>A single bounded background job. Notification reservations survive restarts and generation
 * failures.
 */
public final class BehaviorService {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(BehaviorService.class);

  /** 設定。 */
  private final BehaviorProperties config;

  /** 作業状況。 */
  private final ActivityProperties activity;

  /** 取得記録。 */
  private final ActivityCapture capture;

  /** 時系列の記録。 */
  private final ActivityStore timeline;

  /** 状態の保存先。 */
  private final BehaviorStateStore persistence;

  /** 評価の判断。 */
  private final BehaviorEvaluator evaluator;

  /** 判断基準。 */
  private final BehaviorNotificationPolicy policy;

  /** 生成。 */
  private final BehaviorMessageGenerator generator;

  /** 通知先。 */
  private final AgentMessagePublisher publisher;

  /** 追跡。 */
  private final AgentActivityTracker tracker;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 実行中。 */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 上書き。 */
  private Boolean override;

  /** 生成。 */
  private long generation;

  /** 状態。 */
  private BehaviorState state;

  /** 評価。 */
  private volatile BehaviorAssessment assessment;

  /** 通知の抑制。 */
  private volatile String suppression = "NOT_EVALUATED";

  /** 指定された値や利用先を使って、BehaviorServiceを初期化する。 */
  public BehaviorService(
      BehaviorProperties config,
      ActivityProperties activity,
      ActivityCapture capture,
      ActivityStore timeline,
      BehaviorStateStore persistence,
      BehaviorMessageGenerator generator,
      AgentMessagePublisher publisher,
      AgentActivityTracker tracker,
      Clock clock) {
    config.validate();
    this.config = config;
    this.activity = activity;
    this.capture = capture;
    this.timeline = timeline;
    this.persistence = persistence;
    this.generator = generator;
    this.publisher = publisher;
    this.tracker = tracker;
    this.clock = clock;
    evaluator = new BehaviorEvaluator(config, activity.getPrimaryConfidenceThreshold());
    policy = new BehaviorNotificationPolicy(config);
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public synchronized boolean enabled() {
    return override == null ? config.isEnabled() : override;
  }

  /** 作業習慣の支援で使用する有効状態を設定する。 */
  public synchronized void setEnabled(boolean value) {
    override = value;
    generation++;
  }

  /** 作業習慣の支援において、許可に該当するか判定する。 */
  private synchronized boolean allowed(long token) {
    return enabled() && activity.isEnabled() && !capture.isPaused() && token == generation;
  }

  /** 対象を判断基準に照らして評価する。 */
  public BehaviorAssessment evaluate() {
    Instant now = clock.instant(), start = now.minusSeconds(config.getHistoryMinutes() * 60L);
    var fine = timeline.findBetween(start, now);
    var raw = timeline.findRecordsBetween(start, now);
    var result = evaluator.evaluate(fine, raw, now);
    assessment = result;
    return result;
  }

  /** 作業習慣の支援に関する定期確認を処理する。 */
  public void tick() {
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!running.compareAndSet(false, true)) return;
    BehaviorAssessment attempted = null;
    try {
      long token;
      synchronized (this) {
        token = generation;
      }
      if (!allowed(token)) return;
      var a = evaluate();
      BehaviorNotification notification;
      long activityVersion;
      synchronized (this) {
        if (!allowed(token)) return;
        if (state == null) state = Objects.requireNonNull(persistence.load());
        var decision = policy.decide(a, state, true, tracker.isAgentBusy());
        suppression = decision.suppression().name();
        var next = decision.allowed() ? decision.state().notified(a) : decision.state();
        if (!next.equals(state)) {
          persistence.save(next, a);
          state = next;
        }
        if (!decision.allowed()) {
          recordEvent(a, clock.instant(), BehaviorTimelineEvent.Outcome.SUPPRESSED, suppression);
          return;
        }
        notification = new BehaviorNotification(state.episodeId(), a);
        activityVersion = tracker.activityVersion();
        attempted = a;
      }
      // No monitor is held during LLM inference. Turning off or starting chat invalidates the
      // delivery.
      String message = generator.generate(notification);
      if (!allowed(token)) {
        recordEvent(
            a, clock.instant(), BehaviorTimelineEvent.Outcome.SUPPRESSED, "CONTEXT_CHANGED");
        return;
      }
      var current = evaluate();
      synchronized (this) {
        if (!allowed(token)
            || tracker.isAgentBusy()
            || tracker.activityVersion() != activityVersion
            || !current.activeEntertainment()
            || !current.episodeQualified()
            || current.severity().ordinal() < a.severity().ordinal()
            || current.recoveredAt() != null
                && (a.recoveredAt() == null || current.recoveredAt().isAfter(a.recoveredAt()))) {
          suppression = "CONTEXT_CHANGED";
          recordEvent(a, clock.instant(), BehaviorTimelineEvent.Outcome.SUPPRESSED, suppression);
          return;
        }
        if (message == null || message.isBlank())
          throw new IllegalStateException("Empty behavior message");
        var emittedAt = clock.instant();
        var notificationId = UUID.randomUUID().toString();
        publisher.publish(
            new AgentMessage(
                notificationId,
                "assistant",
                message,
                MessageOrigin.BEHAVIOR,
                emittedAt,
                Map.of("severity", a.severity().name(), "triggerType", a.reason().name())));
        suppression = "DELIVERED";
        recordEvent(
            a, emittedAt, BehaviorTimelineEvent.Outcome.EMITTED, suppression, notificationId);
      }
    } catch (Exception error) {
      suppression = "FAILED";
      if (attempted != null)
        recordEvent(attempted, clock.instant(), BehaviorTimelineEvent.Outcome.FAILED, suppression);
      log.warn("Behavior evaluation/notification failed ({})", error.getClass().getSimpleName());
    } finally {
      running.set(false);
    }
  }

  /** 後から経緯や使用量を確認できるよう、イベントを記録する。 */
  private void recordEvent(
      BehaviorAssessment a, Instant at, BehaviorTimelineEvent.Outcome outcome, String reason) {
    recordEvent(a, at, outcome, reason, UUID.randomUUID().toString());
  }

  /** 後から経緯や使用量を確認できるよう、イベントを記録する。 */
  private void recordEvent(
      BehaviorAssessment a,
      Instant at,
      BehaviorTimelineEvent.Outcome outcome,
      String reason,
      String id) {
    if (a.severity() == BehaviorSeverity.NONE) return;
    try {
      persistence.appendEvent(
          new BehaviorTimelineEvent(
              id,
              at,
              a.severity(),
              a.reason(),
              outcome,
              reason,
              a.continuousEntertainmentSeconds(),
              a.windows()));
    } catch (Exception e) {
      log.warn("Behavior timeline history unavailable; delivery policy unchanged");
    }
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public synchronized String status() {
    if (state == null) state = Objects.requireNonNull(persistence.load());
    var a = assessment;
    var until = policy.cooldownUntil(state);
    return "Behavior enabled="
        + enabled()
        + "; severity="
        + (a == null ? state.currentSeverity() + " (保存値)" : a.severity())
        + "; continuousObservedMinutes="
        + (a == null ? 0 : a.continuousEntertainmentSeconds() / 60.0)
        + "; cooldownUntil="
        + (until == null ? "none" : until)
        + "; notification="
        + suppression;
  }
}
