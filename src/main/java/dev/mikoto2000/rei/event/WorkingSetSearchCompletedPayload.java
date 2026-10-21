package dev.mikoto2000.rei.event;

/**
 * 実行状況の通知で受け渡す作業中集合検索完了通知内容を表す。
 *
 * <p>Aggregate-only result of a Working Set search lifecycle.
 *
 * @param searchId 検索識別子
 * @param durationMs 継続時間ミリ秒
 * @param hitCount 検索結果件数
 * @param candidateCount 候補件数
 * @param selectedCount 選択中件数
 * @param alreadyPresentCount 既に存在する件数
 * @param workingSetSizeBefore 作業中集合大きさ変更前
 * @param workingSetSizeAfter 作業中集合大きさ変更後
 */
public record WorkingSetSearchCompletedPayload(
    String searchId,
    long durationMs,
    int hitCount,
    int candidateCount,
    int selectedCount,
    int alreadyPresentCount,
    int workingSetSizeBefore,
    int workingSetSizeAfter)
    implements AgentEventPayload {}
