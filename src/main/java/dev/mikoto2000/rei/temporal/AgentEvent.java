package dev.mikoto2000.rei.temporal;

import dev.mikoto2000.rei.json.JsonObjectSnapshot;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 実行中に発生した出来事を、会話や実行と関連付けて通知する共通形式。
 *
 * @param timestamp 記録日時
 * @param type 種別
 * @param subject 対象
 * @param payload 通知内容
 */
public record AgentEvent(
    Instant timestamp, String type, String subject, Map<String, Object> payload) {
  /** 通知や要求の内容を、受け取った時点の状態で保持する。 */
  public AgentEvent {
    payload = payload == null ? null : Collections.unmodifiableMap(JsonObjectSnapshot.copy(payload));
  }
}
