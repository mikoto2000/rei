package dev.mikoto2000.rei.activity;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 作業状況の把握に必要な期間助言を扱う。
 *
 * <p>User-defined category-share comparison, not a productivity or entertainment classifier.
 */
public final class PeriodCoaching {
  /**
   * 作業状況の把握で受け渡す設定を表す。
   *
   * @param enabled 有効状態
   * @param categories 分類一覧
   * @param targetShare 対象共有
   * @param minimumObservedMinutes 最小観測分数
   * @param minimumCoverage 最小対象範囲
   * @param maximumUnknownShare 最大不明共有
   * @param cooldownDays 通知を控える期間日数
   */
  public record Settings(
      boolean enabled,
      Set<String> categories,
      double targetShare,
      int minimumObservedMinutes,
      double minimumCoverage,
      double maximumUnknownShare,
      int cooldownDays) {
    /** 受け取った情報からSettingsを作成する。 */
    public Settings {
      if (categories == null
          || categories.isEmpty()
          || !ActivityVocabulary.CATEGORIES.containsAll(categories)
          || !Collections.disjoint(categories, Set.of("unknown", "other"))
          || !Double.isFinite(targetShare)
          || targetShare <= 0
          || targetShare > 1
          || minimumObservedMinutes < 1
          || minimumObservedMinutes > 44640
          || !Double.isFinite(minimumCoverage)
          || minimumCoverage <= 0
          || minimumCoverage > 1
          || !Double.isFinite(maximumUnknownShare)
          || maximumUnknownShare < 0
          || maximumUnknownShare > 1
          || cooldownDays < 1
          || cooldownDays > 366) throw new IllegalArgumentException("Invalid coaching criteria");
      categories = Set.copyOf(categories);
    }

    /** 作業状況の把握で用いる既定値を返す。 */
    public static Settings defaults() {
      return new Settings(
          false, Set.of("development", "research", "documentation"), .6, 120, .1, .25, 7);
    }

    /** 指定された有効状態を反映した情報を作成する。 */
    public Settings withEnabled(boolean enabled) {
      return new Settings(
          enabled,
          categories,
          targetShare,
          minimumObservedMinutes,
          minimumCoverage,
          maximumUnknownShare,
          cooldownDays);
    }
  }

  /**
   * 作業状況の把握で受け渡す評価を表す。
   *
   * @param advice 助言
   * @param reason 理由
   * @param currentShare 現在共有
   * @param previousShare 前回共有
   * @param message メッセージ
   */
  public record Assessment(
      boolean advice, String reason, double currentShare, double previousShare, String message) {}

  /** 対象を判断基準に照らして評価する。 */
  public Assessment evaluate(
      Settings settings,
      ActivityPeriodAnalysis.Aggregate current,
      ActivityPeriodAnalysis.Aggregate previous) {
    if (!settings.enabled()) return suppressed("DISABLED");
    if (current.range().partial() || previous.range().partial())
      return suppressed("PARTIAL_PERIOD");
    for (var a : List.of(current, previous)) {
      long span = a.observedSeconds() + a.unobservedSeconds();
      if (a.observedSeconds() < settings.minimumObservedMinutes() * 60L
          || span <= 0
          || a.observedSeconds() / (double) span < settings.minimumCoverage())
        return suppressed("INSUFFICIENT_OBSERVATION");
      if (unknown(a) / (double) a.observedSeconds() > settings.maximumUnknownShare()
          || a.observedSeconds() <= unknown(a)) return suppressed("UNCERTAIN_CLASSIFICATION");
    }
    double share = share(settings, current), before = share(settings, previous);
    if (share >= settings.targetShare())
      return new Assessment(
          false, "WITHIN_USER_CRITERIA", share, before, "指定基準の範囲内です。成果・生産性の測定ではありません。");
    String reason = before - share >= .1 ? "BELOW_TARGET_DECLINING" : "BELOW_TARGET";
    String message =
        String.format(
            Locale.ROOT,
            "指定分類の推定比率 %.1f%%（直前期間 %.1f%%、指定基準 %.1f%%）。分類可能な観測推定時間が分母です。\n"
                + "基準より低いので、分類の妥当性と今の優先事項を確認し、必要なら指定分類に対応する短い作業枠を試してみてください。\n"
                + "成果・生産性・集中の低下を示す測定ではありません。未観測時間の行動は不明です。",
            share * 100,
            before * 100,
            settings.targetShare() * 100);
    return new Assessment(true, reason, share, before, message);
  }

  /** 確定できない結果を、判定不能の情報として表す。 */
  private static long unknown(ActivityPeriodAnalysis.Aggregate a) {
    return a.categorySeconds().getOrDefault("unknown", 0L)
        + a.categorySeconds().getOrDefault("other", 0L);
  }

  /** 作業状況の把握で用いる共有を返す。 */
  private static double share(Settings settings, ActivityPeriodAnalysis.Aggregate a) {
    return settings.categories().stream()
            .mapToLong(c -> a.categorySeconds().getOrDefault(c, 0L))
            .sum()
        / (double) (a.observedSeconds() - unknown(a));
  }

  /** 作業状況の把握で用いる抑制されたを返す。 */
  private static Assessment suppressed(String reason) {
    return new Assessment(false, reason, 0, 0, "助言を抑制しました: " + reason);
  }
}
