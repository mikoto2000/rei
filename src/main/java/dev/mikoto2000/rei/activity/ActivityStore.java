package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/** 作業状況の記録を保持し、後続の処理で参照できるようにする。 */
public interface ActivityStore {
  /** 既存の記録へ経緯を残すため、作業状況保存先を追記する。 */
  void append(ActivityRecord record);

  /**
   * 指定された内容へ切り替えるため、作業状況保存先を置き換える。
   *
   * <p>Replace an existing observation and rebuild its session projection without adding observed
   * time.
   */
  void replace(ActivityRecord record);

  /** 対象を特定できるよう、期間を探す。 */
  List<ActivitySession> findBetween(Instant start, Instant end);

  /** 対象を特定できるよう、記録一覧期間を探す。 */
  List<ActivityRecord> findRecordsBetween(Instant start, Instant end);

  /**
   * 対象を特定できるよう、記録一覧期間上限内に収めたを探す。
   *
   * <p>Bounded read capability; older adapters must not silently fall back to an unbounded read.
   */
  default List<ActivityRecord> findRecordsBetweenBounded(
      Instant start, Instant end, int maxRecords) {
    throw new UnsupportedOperationException("Bounded Activity evidence queries are unavailable");
  }

  /**
   * 対象を特定できるよう、観測内容一覧期間上限内に収めたを探す。
   *
   * <p>Exact observation instants, end exclusive; unlike interval queries this never scans the
   * preceding day.
   */
  default List<ActivityRecord> findObservationsBetweenBounded(
      Instant start, Instant end, int maxRecords) {
    throw new UnsupportedOperationException("Bounded recent observations are unavailable");
  }
}
