package dev.mikoto2000.rei.activity;

import static dev.mikoto2000.rei.activity.DailySummaryAggregate.Bucket;
import static dev.mikoto2000.rei.activity.DailySummaryAggregate.categoryLabel;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 日次要約集計区分を利用者が確認できる表示形式へ整える。
 *
 * <p>Presentation policy over existing ranks and durations; never reclassifies observations.
 */
public final class DailySummaryBucketFormatter {
  /** 余暇。 */
  private static final Map<String, String> LEISURE =
      Map.of("social", "SNS", "media", "動画・音楽", "shopping", "ショッピング", "gaming", "ゲーム");

  /** 作業状況の把握で用いるredundant表示名一覧を返す。 */
  Set<String> redundantLabels(DailySummaryAggregate a, Bucket b) {
    var hidden = new HashSet<String>();
    for (var entry : LEISURE.entrySet()) {
      String category = entry.getKey(),
          label = categoryLabel(category),
          shortLabel = entry.getValue();
      if (!a.leisureActivities().contains(label) || !appears(b, category, shortLabel)) continue;
      if (a.timeOfDay().values().stream()
              .filter(other -> appears(other, category, shortLabel))
              .count()
          < 2) continue;
      // Retain the existing 65% major-activity rule and a work-free bucket's leading activity.
      boolean leading =
          DailySummaryAggregator.top(b.categorySeconds(), 1).stream()
              .anyMatch(w -> w.name().equals(category));
      if (leading && b.categorySeconds().get(category) >= b.observedSeconds() * .65) continue;
      if (b.workThemes().isEmpty()
          && !b.dominant().isEmpty()
          && b.dominant().getFirst().equals(label)) continue;
      hidden.add(label);
      hidden.add(shortLabel);
    }
    return hidden;
  }

  /** 作業状況の把握において、appearsに該当するか判定する。 */
  private static boolean appears(Bucket b, String category, String shortLabel) {
    return b.categorySeconds().getOrDefault(category, 0L) > 0
        || b.secondary().contains(shortLabel)
        || b.background().contains(shortLabel)
        || b.secondaryThemes().contains(categoryLabel(category));
  }

  /** 受け取った情報を、表示や受け渡しに適した形式へ整える。 */
  public String format(DailySummaryAggregate a, Bucket b) {
    var hidden = redundantLabels(a, b);
    var work = b.workThemes().stream().limit(2).toList();
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (String.join("、", work).length() > 130) work = work.subList(0, 1);
    var nonWork =
        DailySummaryAggregator.top(b.categorySeconds(), 1).stream()
            .filter(
                v ->
                    !Set.of("development", "research", "documentation", "unknown")
                            .contains(v.name())
                        && v.seconds() >= b.observedSeconds() * .65)
            .map(v -> categoryLabel(v.name()))
            .findFirst();
    if (work.isEmpty())
      return ThemeActivityFormatter.join(
              b.dominant().stream().filter(s -> !hidden.contains(s)).toList())
          + "が中心でした。";
    if (nonWork.isPresent()) return nonWork.get() + "が多く、" + String.join("、", work) + "も見られました。";
    var shown = new HashSet<>(work);
    long workSeconds =
        b.timeOfDayThemeCandidates().stream()
            .filter(c -> shown.contains(c.label()))
            .mapToLong(SummaryThemeCandidate::durationSeconds)
            .sum();
    String text =
        String.join("、", work) + (workSeconds >= b.observedSeconds() * .5 ? "が中心でした。" : "も見られました。");
    var secondary = !b.secondaryThemes().isEmpty() ? b.secondaryThemes() : b.secondary();
    var visible = secondary.stream().filter(s -> !hidden.contains(s)).findFirst();
    if (visible.isPresent()) text += visible.get() + "も一部で見られました。";
    return text;
  }
}
