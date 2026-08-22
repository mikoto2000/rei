package dev.mikoto2000.rei.event;

import java.time.Instant;

/**
 * Agent Event の共通 Envelope。
 *
 * <p>すべてのイベントが共通して持つ属性を表す。append-only な「発生した事実」として扱う。</p>
 *
 * @param id イベント自体を一意に識別する ID
 * @param sequence イベントの正規の発生順序を表す単調増加番号
 * @param timestamp イベント発生日時
 * @param type イベント種別
 * @param version イベント schema version（v1 では 1）
 * @param sessionId 会話・セッション単位の識別子
 * @param turnId ユーザー入力 1 回に対応する turn の識別子
 * @param runId Agent Loop の一回の実行単位
 * @param correlationId 一連のイベントを関連付ける ID
 * @param parentEventId 親イベントの ID（任意）
 * @param payload イベント種別ごとのデータ
 */
public record AgentEvent(
    String id,
    long sequence,
    Instant timestamp,
    AgentEventType type,
    int version,
    String sessionId,
    String turnId,
    String runId,
    String correlationId,
    String parentEventId,
    AgentEventPayload payload) {

  public AgentEvent {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp must not be null");
    }
    if (type == null) {
      throw new IllegalArgumentException("type must not be null");
    }
    if (version <= 0) {
      throw new IllegalArgumentException("version must be positive");
    }
  }

  /**
   * sessionId と turnId を設定した複製を返す。
   */
  public AgentEvent withContext(String sessionId, String turnId) {
    return new AgentEvent(id, sequence, timestamp, type, version, sessionId, turnId, runId,
        correlationId, parentEventId, payload);
  }
}
