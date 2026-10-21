package dev.mikoto2000.rei.activity;

import java.time.Instant;

/** 期間助言の記録を保持し、後続の処理で参照できるようにする。 */
public interface PeriodCoachingStore {
  /**
   * 作業状況の把握で受け渡す状態の記録を表す。
   *
   * @param revision 改訂番号
   * @param settings 設定
   */
  record Snapshot(long revision, PeriodCoaching.Settings settings) {}

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  Snapshot load();

  /** 作業状況の把握で用いる設定を返す。 */
  Snapshot configure(PeriodCoaching.Settings settings);

  /** 作業状況の把握で使用する有効状態を設定する。 */
  Snapshot setEnabled(boolean enabled);

  /** 作業状況の把握で用いる設定期待する内容を返す。 */
  default Snapshot configureExpected(PeriodCoaching.Settings settings, long revision) {
    throw new UnsupportedOperationException("Revision-checked coaching updates unavailable");
  }

  /** 作業状況の把握で使用する有効状態期待する内容を設定する。 */
  default Snapshot setEnabledExpected(boolean enabled, long revision) {
    throw new UnsupportedOperationException("Revision-checked coaching updates unavailable");
  }

  /**
   * 競合する処理と利用範囲を調整するため、期間助言保存先を確保する。
   *
   * <p>Atomically recheck settings, duplicate keys and shared cooldown before showing an advice.
   */
  String reserve(Snapshot expected, String periodKey, String reason, Instant now);
}
