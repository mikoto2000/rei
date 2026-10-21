package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.core.execution.ExecutionType;

/**
 * 実行状況の通知で受け渡すバックグラウンド実行通知内容を表す。
 *
 * @param executionId 実行識別子
 * @param executionType 実行種別
 * @param status 状態
 * @param request 要求
 * @param output 出力
 */
public record BackgroundExecutionPayload(
    String executionId, ExecutionType executionType, String status, String request, String output)
    implements AgentEventPayload {}
