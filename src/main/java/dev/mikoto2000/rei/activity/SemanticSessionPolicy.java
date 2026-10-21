package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.activity.ActivityRecord.Activity;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 作業状況の把握で受け渡す意味による会話判断基準を表す。
 *
 * <p>Two stages: compatible primary contexts, then bounded A/B/A excursions for presentation.
 *
 * @param normalGap 通常間隔
 * @param maximumGap 最大間隔
 * @param briefSwitch 要点Switch
 * @param minimumConfidence 最小確信度
 * @param zone タイムゾーン
 */
public record SemanticSessionPolicy(
    Duration normalGap,
    Duration maximumGap,
    Duration briefSwitch,
    double minimumConfidence,
    ZoneId zone) {
  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SemanticSessionPolicy(Duration maximumGap, Duration briefSwitch, ZoneId zone) {
    this(
        maximumGap.compareTo(Duration.ofSeconds(120)) < 0 ? maximumGap : Duration.ofSeconds(120),
        maximumGap,
        briefSwitch,
        .5,
        zone);
  }

  /** 受け取った情報からSemanticSessionPolicyを作成する。 */
  public SemanticSessionPolicy {
    new SessionGapPolicy(normalGap, maximumGap);
    new ActivityRolePolicy(minimumConfidence);
    if (briefSwitch.isNegative()) throw new IllegalArgumentException("Negative summary threshold");
  }

  /**
   * 作業状況の把握で受け渡すサンプルを表す。
   *
   * @param record 記録
   * @param start 開始
   * @param end 終了
   * @param roles 役割一覧
   */
  private record Sample(ActivityRecord record, Instant start, Instant end, ActivityRoles roles) {
    /** 作業状況の把握で用いる秒数を返す。 */
    long seconds() {
      return Duration.between(start, end).getSeconds();
    }
  }

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  public List<SummarySegment> aggregate(List<ActivityRecord> records) {
    return aggregate(records, Instant.MIN, Instant.MAX);
  }

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  public List<SummarySegment> aggregate(List<ActivityRecord> records, Instant from, Instant until) {
    if (!from.isBefore(until)) throw new IllegalArgumentException("Invalid summary range");
    var sorted =
        records.stream()
            .sorted(
                Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id))
            .toList();
    var groups = new ArrayList<List<Sample>>();
    var rolePolicy = new ActivityRolePolicy(minimumConfidence);
    for (int i = 0; i < sorted.size(); i++) {
      var record = sorted.get(i);
      Instant end = record.capturedAt().plusSeconds(record.durationEstimate());
      Instant midnight =
          record.capturedAt().atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
      if (end.isAfter(midnight)) end = midnight;
      if (i + 1 < sorted.size() && end.isAfter(sorted.get(i + 1).capturedAt()))
        end = sorted.get(i + 1).capturedAt();
      if (end.isAfter(until)) end = until;
      Instant start = record.capturedAt().isBefore(from) ? from : record.capturedAt();
      if (!start.isBefore(end)) continue;
      var sample = new Sample(record, start, end, rolePolicy.classify(record));
      if (!groups.isEmpty()
          && adjacent(groups.getLast().getLast(), sample)
          && compatible(groups.getLast(), sample)
          && gapAllows(groups.getLast(), List.of(sample))) groups.getLast().add(sample);
      else groups.add(new ArrayList<>(List.of(sample)));
    }
    // Only a short, observed return to the same context is smoothed. A sustained new primary stays
    // separate.
    for (int i = 0; i + 2 < groups.size(); ) {
      var a = groups.get(i);
      var b = groups.get(i + 1);
      var c = groups.get(i + 2);
      if (Duration.between(b.getFirst().start(), b.getLast().end()).compareTo(briefSwitch) <= 0
          && adjacent(a.getLast(), b.getFirst())
          && adjacent(b.getLast(), c.getFirst())
          && gapAllows(a, b)
          && gapAllows(join(a, b), c)
          && projectsCompatible(join(a, b), c)
          && c.stream().allMatch(s -> compatible(a, s))) {
        a.addAll(b);
        a.addAll(c);
        groups.remove(i + 2);
        groups.remove(i + 1);
      } else i++;
    }
    return groups.stream().map(this::segment).toList();
  }

  /** 作業状況の把握で用いる統合を返す。 */
  private static List<Sample> join(List<Sample> a, List<Sample> b) {
    var result = new ArrayList<>(a);
    result.addAll(b);
    return result;
  }

  /** 作業状況の把握において、プロジェクト一覧互換に該当するか判定する。 */
  private boolean projectsCompatible(List<Sample> a, List<Sample> b) {
    return join(a, b).stream()
            .map(s -> s.roles().primary())
            .filter(Objects::nonNull)
            .map(Activity::projectCandidate)
            .filter(p -> !p.isBlank())
            .distinct()
            .count()
        <= 1;
  }

  /** 作業状況の把握において、間隔Allowsに該当するか判定する。 */
  private boolean gapAllows(List<Sample> a, List<Sample> b) {
    var all = join(a, b);
    long observed = all.stream().mapToLong(Sample::seconds).sum();
    long missing =
        Duration.between(all.getFirst().start(), all.getLast().end()).getSeconds() - observed;
    return new SessionGapPolicy(normalGap, maximumGap)
        .allows(
            Duration.between(a.getLast().end(), b.getFirst().start()),
            missing,
            strongAgreement(a.getLast().roles(), b.getFirst().roles()));
  }

  /** 作業状況の把握において、確かな合意の程度に該当するか判定する。 */
  public boolean strongAgreement(ActivityRoles a, ActivityRoles b) {
    var x = a.primary();
    var y = b.primary();
    if (x == null || y == null || !ActivityRolePolicy.sameContext(x, y)) return false;
    return (!x.projectCandidate().isBlank() && x.projectCandidate().equals(y.projectCandidate()))
        || (!x.service().isBlank()
            && x.service().equals(y.service())
            && x.application().equals(y.application()));
  }

  /** 作業状況の把握において、adjacentに該当するか判定する。 */
  private boolean adjacent(Sample a, Sample b) {
    return Objects.equals(a.record().continuityId(), b.record().continuityId())
        && a.start().atZone(zone).toLocalDate().equals(b.start().atZone(zone).toLocalDate())
        && Duration.between(a.end(), b.start()).compareTo(maximumGap) <= 0;
  }

  /** 作業状況の把握において、互換に該当するか判定する。 */
  private boolean compatible(List<Sample> group, Sample sample) {
    Activity primary = group.getFirst().roles().primary(), next = sample.roles().primary();
    if (primary == null || next == null) {
      return primary == null
          && next == null
          && Objects.equals(group.getFirst().record().foreground(), sample.record().foreground())
          && new HashSet<>(group.getFirst().record().inference().activities())
              .equals(new HashSet<>(sample.record().inference().activities()));
    }
    if (!ActivityRolePolicy.sameContext(primary, next)) return false;
    // A missing project candidate must not bridge two conflicting known projects.
    String project = ActivityRolePolicy.normalize(next.projectCandidate());
    return project.isEmpty()
        || group.stream()
            .map(s -> s.roles().primary())
            .filter(Objects::nonNull)
            .filter(a -> ActivityRolePolicy.category(a).equals(ActivityRolePolicy.category(next)))
            .map(a -> ActivityRolePolicy.normalize(a.projectCandidate()))
            .filter(p -> !p.isEmpty())
            .allMatch(project::equals);
  }

  /** 作業状況の把握で用いる区間を返す。 */
  private SummarySegment segment(List<Sample> samples) {
    var votes = new LinkedHashMap<Activity, Long>();
    for (var s : samples)
      if (s.roles().primary() != null) votes.merge(s.roles().primary(), s.seconds(), Long::sum);
    Activity primary = null;
    long best = -1;
    for (var candidate : votes.keySet()) {
      long total =
          votes.entrySet().stream()
              .filter(e -> ActivityRolePolicy.sameContext(candidate, e.getKey()))
              .mapToLong(Map.Entry::getValue)
              .sum();
      if (total > best
          || (total == best && votes.get(candidate) > votes.getOrDefault(primary, 0L))) {
        best = total;
        primary = candidate;
      }
    }
    var secondary = new LinkedHashSet<Activity>();
    var background = new LinkedHashSet<Activity>();
    long seconds = samples.stream().mapToLong(Sample::seconds).sum();
    double weighted = 0;
    long supporting = 0;
    for (var s : samples) {
      if (primary != null && ActivityRolePolicy.sameRole(primary, s.roles().primary())) {
        weighted += s.seconds() * s.roles().confidence();
        supporting += s.seconds();
      }
      for (var a :
          s.record().inference().activities().stream()
              .map(ActivityVocabulary::canonical)
              .toList()) {
        if (primary != null && ActivityRolePolicy.sameRole(primary, a)) continue;
        // A monitor brought to the foreground is a secondary activity during an excursion, not
        // passive background.
        if (ActivityRolePolicy.backgroundCandidate(a) && !a.equals(s.roles().primary()))
          background.add(a);
        else secondary.add(a);
      }
    }
    background.removeAll(secondary);
    double confidence = supporting == 0 ? 0 : weighted / supporting;
    for (int i = 1; i < samples.size(); i++)
      if (Duration.between(samples.get(i - 1).end(), samples.get(i).start()).compareTo(normalGap)
          > 0) {
        confidence *= .85;
        break;
      }
    if (confidence < minimumConfidence || supporting * 2 < seconds) {
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
            primary == null
                ? List.of("foreground_unresolved")
                : List.of("foreground_evidence", "observed_duration_weighting"));
    return new SummarySegment(
        samples.getFirst().start(),
        samples.getLast().end(),
        seconds,
        roles,
        samples.stream().map(Sample::record).toList(),
        List.of(),
        SummaryGroupingPolicy.theme(roles),
        samples.stream()
            .map(s -> s.roles().category())
            .filter(c -> !c.equals("unknown"))
            .distinct()
            .toList());
  }
}
