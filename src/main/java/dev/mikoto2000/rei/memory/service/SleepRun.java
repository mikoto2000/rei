package dev.mikoto2000.rei.memory.service;

/**
 * 長期記憶の保存と検索で受け渡す待機実行を表す。
 *
 * @param id 識別子
 * @param sessionId 会話の識別子
 * @param projectId 所属プロジェクトの識別子
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 * @param status 状態
 * @param fromSequence 起点発生順序
 * @param toSequence 終点発生順序
 * @param processedTurns 処理済みやり取り一覧
 * @param candidateCount 候補件数
 * @param added 追加済み
 * @param updated 更新
 * @param merged 統合済み
 * @param superseded 置き換え済みの内容
 * @param ignored 無視する対象
 * @param conflicts 競合一覧
 * @param failed 失敗
 */
public record SleepRun(
    String id,
    String sessionId,
    String projectId,
    String startedAt,
    String completedAt,
    String status,
    long fromSequence,
    long toSequence,
    int processedTurns,
    int candidateCount,
    int added,
    int updated,
    int merged,
    int superseded,
    int ignored,
    int conflicts,
    int failed) {}
