package dev.mikoto2000.rei.temporal;

import java.time.Instant;
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
    Instant timestamp, String type, String subject, Map<String, Object> payload) {}
