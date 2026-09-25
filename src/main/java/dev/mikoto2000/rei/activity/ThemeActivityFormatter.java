package dev.mikoto2000.rei.activity;

import java.util.List;

/** Deterministic wording only; preserves the selected activities and their order. */
public final class ThemeActivityFormatter {
  private ThemeActivityFormatter() {}

  public static String format(String theme,List<String> categories,boolean project) {
    var activities=categories.stream().map(DailySummaryAggregate::categoryLabel).toList();
    if(activities.isEmpty())return theme;
    String detail=join(activities);
    if(theme.isBlank())return detail;
    if(activities.size()==1)return theme+(theme.endsWith(detail)?"":(project?" の":"の")+detail);
    return theme+(project?" に関する":"に関する")+detail;
  }

  static String join(List<String> activities) {
    if(activities.size()<2)return String.join("",activities);
    return activities.getFirst()+"や"+String.join("、",activities.subList(1,activities.size()));
  }
}
