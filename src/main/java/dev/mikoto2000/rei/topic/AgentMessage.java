package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.Map;

/**
 * 話題の管理で受け渡すエージェントメッセージを表す。
 *
 * @param id 識別子
 * @param role 役割
 * @param content 内容
 * @param origin 起点
 * @param createdAt 作成日時
 * @param metadata 付随情報
 */
public record AgentMessage(
    String id,
    String role,
    String content,
    MessageOrigin origin,
    Instant createdAt,
    Map<String, String> metadata) {
  /** 受け取った情報からAgentMessageを作成する。 */
  public AgentMessage {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  /** 話題の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AgentMessage(
      String id, String role, String content, MessageOrigin origin, Instant createdAt) {
    this(id, role, content, origin, createdAt, Map.of());
  }
}
