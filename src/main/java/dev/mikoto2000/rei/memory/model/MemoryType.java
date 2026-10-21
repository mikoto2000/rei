package dev.mikoto2000.rei.memory.model;

/** 長期記憶の表現における記憶種別の選択肢。 */
public enum MemoryType {
  /** 事実。 */
  FACT,
  /** 利用者の好み。 */
  PREFERENCE,
  /** 制約。 */
  CONSTRAINT,
  /** プロジェクト状態。 */
  PROJECT_STATE,
  /** 手順に関する記憶。 */
  PROCEDURE,
  /** 教訓。 */
  LESSON,
  /** 関係。 */
  RELATION,
  /** 利用者利用者の好み。 */
  USER_PREFERENCE,
  /** プロジェクト文脈。 */
  PROJECT_CONTEXT,
  /** 判断結果。 */
  DECISION,
  /** タスク。 */
  TASK,
  /** 知識。 */
  KNOWLEDGE,
  /** 経験の記録要約。 */
  EPISODE_SUMMARY,
  /** 一時的な文脈。 */
  TEMPORARY_CONTEXT
}
