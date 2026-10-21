package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.activity.ActivityRecord.Activity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 作業状況の把握で受け渡す要約Grouping判断基準を表す。
 *
 * <p>Human review themes are broader than primary categories, but never bypass missing-time limits.
 *
 * @param policy 判断基準
 */
public record SummaryGroupingPolicy(SemanticSessionPolicy policy) {
  /** 作業状況の把握で用いるテーマを返す。 */
  public static String theme(ActivityRoles roles) {
    return switch (roles.category()) {
      case "development", "research", "documentation" -> "work-development";
      case "social", "media", "shopping" -> "web-browsing";
      default -> roles.category();
    };
  }

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  public List<SummarySegment> aggregate(List<SummarySegment> sessions) {
    var groups = new ArrayList<List<SummarySegment>>();
    for (var s : sessions) {
      if (!groups.isEmpty() && canJoin(groups.getLast(), s)) groups.getLast().add(s);
      else groups.add(new ArrayList<>(List.of(s)));
    }
    return groups.stream().map(g -> g.size() == 1 ? g.getFirst() : combine(g)).toList();
  }

  /** 作業状況の把握において、統合できるか判断する。 */
  private boolean canJoin(List<SummarySegment> group, SummarySegment b) {
    var first = group.getFirst();
    var a = group.getLast();
    if (first.theme().equals("unknown") || !first.theme().equals(b.theme())) return false;
    if (!a.startedAt()
        .atZone(policy.zone())
        .toLocalDate()
        .equals(b.startedAt().atZone(policy.zone()).toLocalDate())) return false;
    if (a.evidence().isEmpty()
        || b.evidence().isEmpty()
        || !Objects.equals(
            a.evidence().getLast().continuityId(), b.evidence().getFirst().continuityId()))
      return false;
    var projects = new HashSet<String>();
    for (var s : group)
      for (var r : s.evidence()) {
        var primary = new ActivityRolePolicy(policy.minimumConfidence()).classify(r).primary();
        if (primary != null && !primary.projectCandidate().isBlank())
          projects.add(primary.projectCandidate());
      }
    for (var r : b.evidence()) {
      var primary = new ActivityRolePolicy(policy.minimumConfidence()).classify(r).primary();
      if (primary != null && !primary.projectCandidate().isBlank())
        projects.add(primary.projectCandidate());
    }
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (projects.size() > 1) return false;
    if (first.theme().equals("work-development")
        && !a.roles().category().equals(b.roles().category())
        && (a.roles().primary() == null
            || b.roles().primary() == null
            || a.roles().primary().projectCandidate().isBlank()
            || !a.roles()
                .primary()
                .projectCandidate()
                .equals(b.roles().primary().projectCandidate()))) return false;
    long observed =
        group.stream().mapToLong(SummarySegment::observedSeconds).sum() + b.observedSeconds();
    long missing = Duration.between(first.startedAt(), b.endedAt()).getSeconds() - observed;
    return new SessionGapPolicy(policy.normalGap(), policy.maximumGap())
        .allows(
            Duration.between(a.endedAt(), b.startedAt()),
            missing,
            policy.strongAgreement(a.roles(), b.roles()));
  }

  /** 作業状況の把握で用いる統合を返す。 */
  private SummarySegment combine(List<SummarySegment> group) {
    var votes = new LinkedHashMap<Activity, Long>();
    for (var s : group)
      if (s.roles().primary() != null)
        votes.merge(s.roles().primary(), s.observedSeconds(), Long::sum);
    Activity primary = null;
    long largest = -1;
    for (var a : votes.keySet()) {
      long total =
          votes.entrySet().stream()
              .filter(e -> e.getKey().type().equals(a.type()))
              .mapToLong(Map.Entry::getValue)
              .sum();
      if (total > largest) {
        primary = a;
        largest = total;
      }
    }
    var secondary = new LinkedHashSet<Activity>();
    var background = new LinkedHashSet<Activity>();
    long observed = group.stream().mapToLong(SummarySegment::observedSeconds).sum();
    double confidence =
        group.stream().mapToDouble(s -> s.roles().confidence() * s.observedSeconds()).sum()
            / observed;
    for (int i = 1; i < group.size(); i++)
      if (Duration.between(group.get(i - 1).endedAt(), group.get(i).startedAt())
              .compareTo(policy.normalGap())
          > 0) {
        confidence *= .85;
        break;
      }
    for (var s : group) {
      if (s.roles().primary() != null && !ActivityRolePolicy.sameRole(primary, s.roles().primary()))
        secondary.add(s.roles().primary());
      secondary.addAll(s.roles().secondary());
      background.addAll(s.roles().background());
    }
    var selected = primary;
    secondary.removeIf(a -> ActivityRolePolicy.sameRole(selected, a));
    background.removeAll(secondary);
    if (confidence < policy.minimumConfidence()) {
      if (primary != null) secondary.add(primary);
      primary = null;
      confidence = 0;
    }
    var roles =
        new ActivityRoles(
            primary,
            List.copyOf(secondary),
            List.copyOf(background),
            confidence,
            List.of("summary_theme_grouping", "observed_duration_weighting"));
    return new SummarySegment(
        group.getFirst().startedAt(),
        group.getLast().endedAt(),
        observed,
        roles,
        group.stream().flatMap(s -> s.evidence().stream()).distinct().toList(),
        group.stream().flatMap(s -> s.fineSessionIds().stream()).distinct().toList(),
        primary == null ? "unknown" : group.getFirst().theme(),
        group.stream().flatMap(s -> s.primaryCategories().stream()).distinct().toList());
  }
}
