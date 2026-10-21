package dev.mikoto2000.rei.briefing;

/** 日次情報の案内に必要な日次案内読み上げの案内を扱う。 */
public interface BriefingNarrator {

  /** 日次情報の案内で用いるnarrateを返す。 */
  BriefingNarration narrate(BriefingContext context);
}
