package dev.mikoto2000.rei.checkpoint;

/**
 * 作業の中断と再開に必要な再開用の記録履歴を扱う。
 *
 * <p>Global checkpoint snapshot accounting. Event keys are retained for replay deduplication.
 */
public final class CheckpointHistory {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private CheckpointHistory() {}

  /**
   * 作業の中断と再開で受け渡す使用状況を表す。
   *
   * @param revisions 改訂一覧
   * @param eventKeys イベントキー一覧
   * @param snapshotBytes 状態の記録バイト数
   * @param maxRevisions 最大改訂一覧
   * @param maxBytes バイト数の上限
   */
  public record Usage(
      long revisions, long eventKeys, long snapshotBytes, long maxRevisions, long maxBytes) {}

  /**
   * 作業の中断と再開で受け渡す後片付けを表す。
   *
   * @param removedRevisions 削除済み改訂一覧
   * @param removedBytes 削除済みバイト数
   * @param protectedTasks 保護されたタスク一覧
   * @param before 変更前
   * @param after 変更後
   * @param applied 反映済みの内容
   * @param protectedRevisions 保護された改訂一覧
   * @param protectedBytes 保護されたバイト数
   * @param force 強制
   */
  public record Cleanup(
      long removedRevisions,
      long removedBytes,
      long protectedTasks,
      Usage before,
      Usage after,
      boolean applied,
      long protectedRevisions,
      long protectedBytes,
      boolean force) {
    /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Cleanup(
        long removedRevisions,
        long removedBytes,
        long protectedTasks,
        Usage before,
        Usage after,
        boolean applied,
        long protectedRevisions,
        long protectedBytes) {
      this(
          removedRevisions,
          removedBytes,
          protectedTasks,
          before,
          after,
          applied,
          protectedRevisions,
          protectedBytes,
          false);
    }

    /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Cleanup(
        long removedRevisions,
        long removedBytes,
        long protectedTasks,
        Usage before,
        Usage after,
        boolean applied) {
      this(removedRevisions, removedBytes, protectedTasks, before, after, applied, -1, -1, false);
    }
  }
}
