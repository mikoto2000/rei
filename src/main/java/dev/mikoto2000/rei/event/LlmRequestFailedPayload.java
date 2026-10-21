package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す言語モデル要求失敗通知内容を表す。
 *
 * @param requestId 要求の識別子
 * @param durationMs 継続時間ミリ秒
 * @param error エラー
 */
public record LlmRequestFailedPayload(String requestId, long durationMs, ErrorInformation error)
    implements AgentEventPayload {}
