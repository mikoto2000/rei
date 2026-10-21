package dev.mikoto2000.rei.memory.model;

/** 長期記憶の表現における記憶対象範囲の選択肢。 */
public enum MemoryScope {
  /** 全体共通。 */
  GLOBAL,
  /** 会話。 */
  SESSION,
  /** 短期の保持範囲。 */
  SHORT_TERM,
  /** プロジェクト。 */
  PROJECT,
  /** 長期の保持範囲。 */
  LONG_TERM,
  /** 継続する。 */
  PERMANENT
}
