package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す作業文脈通知内容を表す。
 *
 * <p>No project text or tool output is emitted; the source run is a reference, not a new execution.
 *
 * @param sourceRunId 取得元実行の識別子
 * @param revision 改訂番号
 * @param status 状態
 */
public record WorkContextPayload(String sourceRunId, Long revision, String status)
    implements AgentEventPayload {}
