package dev.mikoto2000.rei.memory.service;

/** 長期記憶の保存と検索における記憶操作の選択肢。 */
public enum MemoryAction {
  /** 新規。 */
  NEW,
  /** 重複の有無。 */
  DUPLICATE,
  /** 更新。 */
  UPDATE,
  /** 統合。 */
  MERGE,
  /** 競合。 */
  CONFLICT,
  /** 既存の記憶を新しい内容で置き換える操作。 */
  SUPERSEDE,
  /** 無視する対象。 */
  IGNORE
}
