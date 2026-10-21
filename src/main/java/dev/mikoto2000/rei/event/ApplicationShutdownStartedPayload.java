package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡すアプリケーション終了開始通知内容を表す。
 *
 * <p>Process-wide lifecycle notification, independent of projects and runs.
 *
 * @param reason 理由
 */
public record ApplicationShutdownStartedPayload(String reason) implements AgentEventPayload {}
