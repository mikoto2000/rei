package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.activity.DesktopActivityObserver.Lightweight;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 作業状況の把握に必要な入力Aware観測内容を扱う。
 *
 * <p>Successful persistence owns the baseline. All state transitions share one monitor.
 */
final class InputAwareObservation {
  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 実行間隔／最大。 */
  private final int interval, maximum;

  /** 確認済み／比較の基準／試行。 */
  private Lightweight checked, baseline, attempt;

  /** 確認済み日時／保存済み日時。 */
  private Instant checkedAt, savedAt;

  /** 使用中／forced／保存済み。 */
  private boolean busy, forced = true, saved;

  /** 改訂番号／試行改訂番号。 */
  private long revision, attemptRevision;

  /** 指定された値や利用先を使って、InputAwareObservationを初期化する。 */
  InputAwareObservation(Clock clock, int interval, int maximum) {
    this.clock = clock;
    this.interval = interval;
    this.maximum = maximum;
  }

  /** 作業状況の把握に関する強制を処理する。 */
  synchronized void force() {
    forced = true;
    revision++;
  }

  /** 作業状況の把握において、開始に該当するか判定する。 */
  synchronized boolean begin(Lightweight current) {
    if (busy) {
      if (changed(attempt, current)) force();
      return false;
    }
    var now = clock.instant();
    boolean discontinuity =
        checkedAt != null
            && (now.isBefore(checkedAt)
                || Duration.between(checkedAt, now).toMillis() > interval * 2000L
                || current != null
                    && checked != null
                    && (current.uptimeMillis() < checked.uptimeMillis()
                        || current.workingMillis() < checked.workingMillis()
                        || (current.uptimeMillis() - checked.uptimeMillis())
                                - (current.workingMillis() - checked.workingMillis())
                            > 1000
                        || Math.abs(
                                (current.uptimeMillis() - checked.uptimeMillis())
                                    - Duration.between(checkedAt, now).toMillis())
                            > interval * 2000L));
    if (discontinuity) force();
    checked = current;
    checkedAt = now;
    if (current != null && current.locked()) {
      force();
      return false;
    }
    boolean uncertain =
        current == null
            || !current.reliable()
            || current.windowId() == null
            || current.uptimeMillis() < 0
            || ((current.uptimeMillis() - current.lastInput()) & 0xffffffffL) > 0x7fffffffL;
    boolean due =
        forced
            || uncertain
            || baseline == null
            || changed(current, baseline)
            || savedAt == null
            || now.isBefore(savedAt)
            || Duration.between(savedAt, now).getSeconds() >= maximum;
    if (!due) return false;
    busy = true;
    saved = false;
    attempt = current;
    attemptRevision = revision;
    return true;
  }

  /** 作業状況の把握に関する保存済みを処理する。 */
  synchronized void saved() {
    if (busy) saved = true;
  }

  /** 作業状況の把握において、lightweight試行に該当するか判定する。 */
  synchronized boolean lightweightAttempt() {
    return busy && attempt != null;
  }

  /** 作業状況の把握に関する終了を処理する。 */
  synchronized void end(Lightweight current) {
    if (!busy) return;
    if (saved && attemptRevision == revision) {
      baseline = attempt;
      savedAt = clock.instant();
      forced = changed(attempt, current);
    } else forced = true;
    busy = false;
    attempt = null;
  }

  /** 作業状況の把握において、変更済みに該当するか判定する。 */
  private static boolean changed(Lightweight a, Lightweight b) {
    return a == null
        || b == null
        || !a.reliable()
        || !b.reliable()
        || a.lastInput() != b.lastInput()
        || !Objects.equals(a.windowId(), b.windowId())
        || !Objects.equals(a.desktop(), b.desktop())
        || a.locked() != b.locked();
  }
}
