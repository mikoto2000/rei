package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.activity.behavior.BehaviorTimelineEvent;
import java.time.Instant;

/**
 * 作業状況の把握に必要な作業状況時系列の記録項目を扱う。
 *
 * <p>Presentation variants retain the distinction between observation intervals and notification
 * instants.
 */
public sealed interface ActivityTimelineEntry {
  /** 作業状況の把握で用いる記録日時を返す。 */
  Instant timestamp();

  /**
   * 作業状況の把握で受け渡す作業状況項目を表す。
   *
   * @param segment 区間
   */
  record ActivityEntry(SummarySegment segment) implements ActivityTimelineEntry {
    /** 作業状況の把握で用いる記録日時を返す。 */
    public Instant timestamp() {
      return segment.startedAt();
    }
  }

  /**
   * 作業状況の把握で受け渡す作業習慣項目を表す。
   *
   * @param event イベント
   */
  record BehaviorEntry(BehaviorTimelineEvent event) implements ActivityTimelineEntry {
    /** 作業状況の把握で用いる記録日時を返す。 */
    public Instant timestamp() {
      return event.timestamp();
    }
  }

  /**
   * 作業状況の把握で受け渡す作業推定項目を表す。
   *
   * @param inference 推定
   */
  record WorkInferenceEntry(WorkActivityInference inference) implements ActivityTimelineEntry {
    /** 作業状況の把握で用いる記録日時を返す。 */
    public Instant timestamp() {
      return inference.windowEnd();
    }
  }
}
