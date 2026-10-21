package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/**
 * 作業状況の把握で受け渡す作業状況会話を表す。
 *
 * @param id 識別子
 * @param startedAt 開始日時
 * @param endedAt 終了日時
 * @param observedSeconds 観測秒数
 * @param recordIds 記録識別子一覧
 * @param inference 推定
 * @param primaryApplication 主要なアプリケーション
 * @param confidence 確信度
 */
public record ActivitySession(
    String id,
    Instant startedAt,
    Instant endedAt,
    long observedSeconds,
    List<String> recordIds,
    ActivityRecord.Inference inference,
    String primaryApplication,
    double confidence) {
  /** 受け取った情報からActivitySessionを作成する。 */
  public ActivitySession {
    recordIds = List.copyOf(recordIds);
  }

  /** 作業状況の把握で用いる未観測秒数を返す。 */
  public long unobservedSeconds() {
    return Math.max(
        0, java.time.Duration.between(startedAt, endedAt).getSeconds() - observedSeconds);
  }
}
