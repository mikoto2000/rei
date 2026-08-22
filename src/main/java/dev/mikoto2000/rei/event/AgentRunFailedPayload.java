package dev.mikoto2000.rei.event;

/**
 * Agent Run の異常終了。
 *
 * <p>Throwable 自体は保持せず、必要な情報だけを抽出した {@link ErrorInformation} を保持する。</p>
 *
 * @param runId Agent Loop の一回の実行単位
 * @param error エラー情報
 */
public record AgentRunFailedPayload(String runId, ErrorInformation error)
    implements AgentEventPayload {
}
