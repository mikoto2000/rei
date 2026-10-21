package dev.mikoto2000.rei.memory.model;

/** 長期記憶の表現における記憶状態の選択肢。 */
public enum MemoryStatus {
  /** 置き換え済みの内容。 */
  SUPERSEDED,
  /** 候補。 */
  CANDIDATE,
  /** 活動中。 */
  ACTIVE,
  /** 現在は利用を推奨しない記憶。 */
  DEPRECATED,
  /** 通常の参照対象から外して保管した記憶。 */
  ARCHIVED,
  /** 削除済み。 */
  DELETED
}
