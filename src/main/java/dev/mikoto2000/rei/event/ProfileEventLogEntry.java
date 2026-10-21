package dev.mikoto2000.rei.event;

import java.time.Instant;
import java.util.Map;

/**
 * 実行状況の通知で受け渡す利用者情報イベントログ項目を表す。
 *
 * @param id 識別子
 * @param sequence 発生順序
 * @param timestamp 記録日時
 * @param type 種別
 * @param version 形式のバージョン
 * @param sessionId 会話の識別子
 * @param turnId やり取りの識別子
 * @param runId 実行の識別子
 * @param correlationId 一連の処理を関連付ける識別子
 * @param parentEventId 親イベントの識別子
 * @param payloadType 通知内容種別
 * @param payload 通知内容
 */
public record ProfileEventLogEntry(
    String id,
    long sequence,
    Instant timestamp,
    String type,
    int version,
    String sessionId,
    String turnId,
    String runId,
    String correlationId,
    String parentEventId,
    String payloadType,
    Map<String, Object> payload) {}
