package dev.mikoto2000.rei.activity;

import java.time.Clock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 期間助言のユースケースを実現する。
 *
 * <p>Deterministic period assessment and shared durable reservation. Callers own delivery.
 */
public final class PeriodCoachingService {
  /** 時系列の記録。 */
  private final ActivityTimeline timeline;

  /** 保存先。 */
  private final Supplier<PeriodCoachingStore.Snapshot> loadSettings;

  private final Function<Boolean, PeriodCoachingStore.Snapshot> changeEnabled;
  private final Function<PeriodCoaching.Settings, PeriodCoachingStore.Snapshot> changeSettings;
  private final BiFunction<PeriodCoaching.Settings, Long, PeriodCoachingStore.Snapshot>
      changeSettingsAtRevision;
  private final BiFunction<Boolean, Long, PeriodCoachingStore.Snapshot> changeEnabledAtRevision;
  private final Function<Prepared, String> reserveAdvice;

  /** 指定された値や利用先を使って、PeriodCoachingServiceを初期化する。 */
  public PeriodCoachingService(ActivityTimeline timeline, PeriodCoachingStore store, Clock clock) {
    this.timeline = timeline;
    this.loadSettings = () -> store.load();
    this.changeEnabled = enabled -> store.setEnabled(enabled);
    this.changeSettings = settings -> store.configure(settings.withEnabled(false));
    this.changeSettingsAtRevision =
        (settings, revision) -> store.configureExpected(settings.withEnabled(false), revision);
    this.changeEnabledAtRevision =
        (enabled, revision) -> store.setEnabledExpected(enabled, revision);
    this.reserveAdvice =
        prepared ->
            store.reserve(
                prepared.settings(), prepared.periodKey(), prepared.reason(), clock.instant());
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public PeriodCoachingStore.Snapshot status() {
    return loadSettings.get();
  }

  /** 作業状況の把握で使用する有効状態を設定する。 */
  public PeriodCoachingStore.Snapshot setEnabled(boolean enabled) {
    return changeEnabled.apply(enabled);
  }

  /** 作業状況の把握で用いる設定を返す。 */
  public PeriodCoachingStore.Snapshot configure(PeriodCoaching.Settings settings) {
    return changeSettings.apply(settings);
  }

  /** 作業状況の把握で用いる設定期待する内容を返す。 */
  public PeriodCoachingStore.Snapshot configureExpected(
      PeriodCoaching.Settings settings, long revision) {
    return changeSettingsAtRevision.apply(settings, revision);
  }

  /** 作業状況の把握で使用する有効状態期待する内容を設定する。 */
  public PeriodCoachingStore.Snapshot setEnabledExpected(boolean enabled, long revision) {
    return changeEnabledAtRevision.apply(enabled, revision);
  }

  /** 対象を判断基準に照らして評価する。 */
  public String evaluate(ActivityPeriodAnalysis.Period period, String day) {
    var prepared = prepare(period, day);
    if (!prepared.advice()) return prepared.message();
    String reservation = reserve(prepared);
    if (!reservation.equals("RESERVED")) return "助言を抑制しました: " + reservation;
    return "手動" + prepared.message();
  }

  /**
   * 作業状況の把握で受け渡す準備済みを表す。
   *
   * @param settings 設定
   * @param periodKey 期間キー
   * @param reason 理由
   * @param message メッセージ
   * @param advice 助言
   */
  public record Prepared(
      PeriodCoachingStore.Snapshot settings,
      String periodKey,
      String reason,
      String message,
      boolean advice) {}

  /**
   * 作業状況の把握で用いる準備を返す。
   *
   * <p>No reservation or side effect; allows automatic callers to recheck their delivery context.
   */
  public Prepared prepare(ActivityPeriodAnalysis.Period period, String day) {
    var settings = loadSettings.get();
    if (!settings.settings().enabled())
      return new Prepared(settings, null, "DISABLED", "助言を抑制しました: DISABLED", false);
    var comparison = timeline.periodComparison(period, day);
    var current = comparison.current();
    var previous = comparison.previous();
    var assessment = new PeriodCoaching().evaluate(settings.settings(), current, previous);
    if (!assessment.advice())
      return new Prepared(settings, null, assessment.reason(), assessment.message(), false);
    String key = period + ":" + current.range().firstDate() + ":" + comparison.zone();
    String message =
        "Coaching: "
            + period
            + " "
            + current.range().firstDate()
            + " / zone="
            + comparison.zone()
            + "\n"
            + "指定分類="
            + new java.util.TreeSet<>(settings.settings().categories())
            + "\n"
            + String.format(
                java.util.Locale.ROOT,
                "観測推定量: 対象 %.1f分 / 前期間 %.1f分",
                current.observedSeconds() / 60.0,
                previous.observedSeconds() / 60.0)
            + "\n"
            + assessment.message();
    return new Prepared(settings, key, assessment.reason(), message, true);
  }

  /** 競合する処理と利用範囲を調整するため、期間助言サービスを確保する。 */
  public String reserve(Prepared prepared) {
    return prepared.advice() ? reserveAdvice.apply(prepared) : prepared.reason();
  }

  /** 作業状況の把握において、現在に該当するか判定する。 */
  public boolean current(Prepared prepared) {
    return loadSettings.get().equals(prepared.settings());
  }
}
