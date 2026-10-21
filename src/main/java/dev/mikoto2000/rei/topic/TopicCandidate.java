package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す話題候補を表す。
 *
 * @param id 識別子
 * @param topic 話題
 * @param reason 理由
 * @param type 種別
 * @param source 取得元
 * @param priority 優先度
 * @param freshness 情報の鮮度
 * @param usefulness 有用性
 * @param intrusiveness 割り込みの負担
 * @param confidence 確信度
 * @param createdAt 作成日時
 */
public record TopicCandidate(
    String id,
    String topic,
    String reason,
    TopicType type,
    TopicSource source,
    double priority,
    double freshness,
    double usefulness,
    double intrusiveness,
    double confidence,
    Instant createdAt) {

  /** 受け取った情報からTopicCandidateを作成する。 */
  public TopicCandidate {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
    if (topic == null || topic.isBlank())
      throw new IllegalArgumentException("topic must not be blank");
    if (reason == null) reason = "";
    if (type == null) throw new IllegalArgumentException("type must not be null");
    if (source == null) throw new IllegalArgumentException("source must not be null");
    priority = clamp(priority);
    freshness = clamp(freshness);
    usefulness = clamp(usefulness);
    intrusiveness = clamp(intrusiveness);
    confidence = clamp(confidence);
    if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
  }

  /** 話題の管理で用いる許容範囲を返す。 */
  private static double clamp(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0d;
    return Math.max(0.0d, Math.min(1.0d, value));
  }
}
