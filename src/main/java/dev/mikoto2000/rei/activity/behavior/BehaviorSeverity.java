package dev.mikoto2000.rei.activity.behavior;

/** 作業習慣の支援における作業習慣重大度の選択肢。 */
public enum BehaviorSeverity {
  /** 該当なし。 */
  NONE,
  /** 通知。 */
  NOTICE,
  /** 注意事項。 */
  WARNING,
  /** 確かな注意事項。 */
  STRONG_WARNING;

  /** 作業習慣の支援で用いる最大を返す。 */
  public static BehaviorSeverity max(BehaviorSeverity a, BehaviorSeverity b) {
    return a.ordinal() >= b.ordinal() ? a : b;
  }
}
