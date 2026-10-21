package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.topic.AgentMessage;
import dev.mikoto2000.rei.topic.AgentMessagePublisher;
import dev.mikoto2000.rei.topic.MessageOrigin;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 作業状況の把握に必要な自動期間助言を扱う。
 *
 * <p>Opt-in, at most two closed-period assessments and one publication per tick. No model calls.
 */
public final class AutomaticPeriodCoaching {
  /** この機能の動作設定。 */
  private final ActivityProperties properties;

  /** 助言。 */
  private final PeriodCoachingService coaching;

  /** 通知先。 */
  private final AgentMessagePublisher publisher;

  /** 追跡。 */
  private final AgentActivityTracker tracker;

  /** 一時停止中。 */
  private final BooleanSupplier paused;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 識別子一覧。 */
  private final Supplier<String> ids;

  /** 実行中。 */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 状態。 */
  private volatile String status = "NOT_EVALUATED";

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AutomaticPeriodCoaching(
      ActivityProperties properties,
      PeriodCoachingService coaching,
      AgentMessagePublisher publisher,
      AgentActivityTracker tracker,
      BooleanSupplier paused,
      Clock clock) {
    this(
        properties,
        coaching,
        publisher,
        tracker,
        paused,
        clock,
        () -> UUID.randomUUID().toString());
  }

  /** 指定された値や利用先を使って、AutomaticPeriodCoachingを初期化する。 */
  public AutomaticPeriodCoaching(
      ActivityProperties properties,
      PeriodCoachingService coaching,
      AgentMessagePublisher publisher,
      AgentActivityTracker tracker,
      BooleanSupplier paused,
      Clock clock,
      Supplier<String> ids) {
    properties.validate();
    this.properties = properties;
    this.coaching = coaching;
    this.publisher = publisher;
    this.tracker = tracker;
    this.paused = paused;
    this.clock = clock;
    this.ids = ids;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public boolean enabled() {
    return properties.isEnabled() && properties.getCoaching().isAutomaticEnabled();
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public String status() {
    return status;
  }

  /** 作業状況の把握において、許可に該当するか判定する。 */
  private boolean allowed() {
    return enabled()
        && publisher != null
        && tracker != null
        && !paused.getAsBoolean()
        && !tracker.isAgentBusy()
        && !Thread.currentThread().isInterrupted();
  }

  /** 作業状況の把握に関する定期確認を処理する。 */
  public void tick() {
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!running.compareAndSet(false, true)) return;
    try {
      if (!allowed()) {
        status = enabled() ? "UNAVAILABLE_OR_BUSY" : "DISABLED";
        return;
      }
      if (!coaching.status().settings().enabled()) {
        status = "DISABLED";
        return;
      }
      long version = tracker.activityVersion();
      status = "NO_PERIODS";
      // A new month gets the first opportunity; weekly/manual advice share the same cooldown.
      for (var period :
          List.of(ActivityPeriodAnalysis.Period.MONTH, ActivityPeriodAnalysis.Period.WEEK)) {
        if (period == ActivityPeriodAnalysis.Period.MONTH
            ? !properties.getCoaching().isMonthlyEnabled()
            : !properties.getCoaching().isWeeklyEnabled()) continue;
        if (!allowed() || tracker.activityVersion() != version) {
          status = "CONTEXT_CHANGED";
          return;
        }
        var prepared = coaching.prepare(period, null);
        status = prepared.reason();
        if (!prepared.advice()) continue;
        if (!allowed() || tracker.activityVersion() != version) {
          status = "CONTEXT_CHANGED";
          return;
        }
        status = coaching.reserve(prepared);
        if (!status.equals("RESERVED")) continue;
        // Never release the receipt: failure/context change after claim is not safe to replay.
        if (!allowed() || tracker.activityVersion() != version || !coaching.current(prepared)) {
          status = "CONTEXT_CHANGED";
          return;
        }
        publisher.publish(
            new AgentMessage(
                ids.get(),
                "assistant",
                "自動" + prepared.message(),
                MessageOrigin.BEHAVIOR,
                clock.instant(),
                Map.of("severity", "NOTICE", "triggerType", "PERIOD_COACHING_" + period)));
        status = "DELIVERED";
        return;
      }
    } catch (RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      status = "FAILED";
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Automatic period coaching failed ({})", error.getClass().getSimpleName());
    } finally {
      running.set(false);
    }
  }
}
