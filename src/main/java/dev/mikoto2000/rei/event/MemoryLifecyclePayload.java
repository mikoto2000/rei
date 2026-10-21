package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す記憶受付から終了までの管理通知内容を表す。
 *
 * <p>Counts and lifecycle only; memory content and source text are never included.
 *
 * @param sleepRunId 待機実行の識別子
 * @param preview 事前確認
 * @param processedTurns 処理済みやり取り一覧
 * @param candidateCount 候補件数
 * @param memoryCount 記憶件数
 * @param status 状態
 */
public record MemoryLifecyclePayload(
    String sleepRunId,
    boolean preview,
    int processedTurns,
    int candidateCount,
    int memoryCount,
    String status)
    implements AgentEventPayload {}
