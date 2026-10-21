package dev.mikoto2000.rei.activity;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 作業状況の把握で受け渡す傾向要約判断基準を表す。
 *
 * <p>Read-only trend grouping. Session gap rules remain intact in the source projections.
 *
 * @param zone タイムゾーン
 * @param minimumConfidence 最小確信度
 * @param maximumWindow 最大範囲
 * @param maximumGap 最大間隔
 * @param softMaximumDuration soft最大継続時間
 */
public record TrendSummaryPolicy(
    ZoneId zone,
    double minimumConfidence,
    Duration maximumWindow,
    Duration maximumGap,
    Duration softMaximumDuration) {
  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public TrendSummaryPolicy(ZoneId zone, double minimumConfidence) {
    this(zone, minimumConfidence, Duration.ofMinutes(60), Duration.ofMinutes(20));
  }

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public TrendSummaryPolicy(
      ZoneId zone, double minimumConfidence, Duration maximumWindow, Duration maximumGap) {
    this(zone, minimumConfidence, maximumWindow, maximumGap, Duration.ofMinutes(45));
  }

  /** 受け取った情報からTrendSummaryPolicyを作成する。 */
  public TrendSummaryPolicy {
    new ActivityRolePolicy(minimumConfidence);
    if (maximumWindow.isNegative()
        || maximumWindow.isZero()
        || maximumGap.isNegative()
        || softMaximumDuration.isNegative()
        || softMaximumDuration.isZero()) throw new IllegalArgumentException("Invalid trend window");
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

    /** 作業状況の把握において、既知に該当するか判定する。 */
    boolean known() {
      return roles.primary() != null && !Set.of("unknown", "other").contains(roles.category());
    }

    /** 作業状況の把握で用いるプロジェクトを返す。 */
    String project() {
      return known() ? ActivityDisplayLabels.project(roles.primary(), record) : "";
    }
  }

  /**
   * 作業状況の把握で受け渡す文脈を表す。
   *
   * @param source 取得元
   * @param samples サンプル一覧
   * @param projects プロジェクト一覧
   * @param families 分類のまとまり
   * @param labels 表示名一覧
   */
  private record Context(
      SummarySegment source,
      List<Sample> samples,
      Set<String> projects,
      Set<String> families,
      Set<String> labels) {}

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  public List<TrendSummarySegment> aggregate(List<SummarySegment> source) {
    var groups = new ArrayList<List<Context>>();
    for (var segment :
        source.stream().sorted(Comparator.comparing(SummarySegment::startedAt)).toList()) {
      var context = context(segment);
      if (context.samples().isEmpty()) continue;
      if (!groups.isEmpty() && joins(groups.getLast(), context)) groups.getLast().add(context);
      else groups.add(new ArrayList<>(List.of(context)));
    }
    return groups.stream().flatMap(group -> split(group).stream()).map(this::summarize).toList();
  }

  /** 作業状況の把握で用いる文脈を返す。 */
  private Context context(SummarySegment segment) {
    var records =
        segment.evidence().stream()
            .distinct()
            .sorted(
                Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id))
            .toList();
    var samples = new ArrayList<Sample>();
    var rolePolicy = new ActivityRolePolicy(minimumConfidence);
    var projects = new TreeSet<String>();
    var families = new TreeSet<String>();
    var labels = new HashSet<String>();
    for (int i = 0; i < records.size(); i++) {
      var r = records.get(i);
      var start =
          r.capturedAt().isBefore(segment.startedAt()) ? segment.startedAt() : r.capturedAt();
      var end = r.capturedAt().plusSeconds(r.durationEstimate());
      if (end.isAfter(segment.endedAt())) end = segment.endedAt();
      if (i + 1 < records.size() && end.isAfter(records.get(i + 1).capturedAt()))
        end = records.get(i + 1).capturedAt();
      if (!start.isBefore(end)) continue;
      var sample = new Sample(r, start, end, rolePolicy.classify(r));
      samples.add(sample);
      if (sample.known()) {
        families.add(family(sample.roles().category()));
        if (!sample.project().isBlank()) projects.add(sample.project());
      }
      for (var a : r.inference().activities()) {
        String label = ActivityDisplayLabels.candidate(a);
        if (!label.isBlank()) labels.add(label);
      }
    }
    return new Context(segment, List.copyOf(samples), projects, families, labels);
  }

  /**
   * 作業状況の把握で用いる分割を返す。
   *
   * <p>Revisit evidence inside a source projection too; source sessions themselves stay unchanged.
   */
  private List<List<Context>> split(List<Context> group) {
    var atoms = new ArrayList<Context>();
    for (var c : group)
      for (var s : c.samples())
        atoms.add(new Context(c.source(), List.of(s), c.projects(), c.families(), c.labels()));
    var result = new ArrayList<List<Context>>();
    splitAtMeaningfulBoundary(atoms, result);
    return result;
  }

  /** 作業状況の把握に関する分割日時Meaningful境界を処理する。 */
  private void splitAtMeaningfulBoundary(List<Context> atoms, List<List<Context>> result) {
    var samples = atoms.stream().map(c -> c.samples().getFirst()).toList();
    boolean overSoft =
        Duration.between(samples.getFirst().start(), samples.getLast().end())
                .compareTo(softMaximumDuration)
            > 0;
    int best = -1, bestRank = Integer.MAX_VALUE;
    long bestBalance = Long.MAX_VALUE;
    String previousProject = "";
    Sample previousKnown = null;
    for (int i = 1; i < samples.size(); i++) {
      var left = samples.get(i - 1);
      var right = samples.get(i);
      if (!left.project().isBlank()) previousProject = left.project();
      if (left.known()) previousKnown = left;
      int rank = Integer.MAX_VALUE;
      if (!right.project().isBlank()
          && !previousProject.isBlank()
          && !right.project().equals(previousProject)) rank = 0;
      if (previousKnown != null && right.known()) {
        if (!family(previousKnown.roles().category()).equals(family(right.roles().category()))
            && stableChange(samples, i, s -> family(s.roles().category())))
          rank = Math.min(rank, 1);
        if (overSoft
            && !previousKnown.roles().category().equals(right.roles().category())
            && stableChange(samples, i, s -> s.roles().category())) rank = Math.min(rank, 5);
        if (overSoft
            && (previousKnown.project().isBlank()
                || !previousKnown.project().equals(right.project()))
            && !foregroundKey(previousKnown).equals(foregroundKey(right))
            && stableChange(samples, i, TrendSummaryPolicy::foregroundKey))
          rank = Math.min(rank, 3);
      }
      if (overSoft
          && Duration.between(left.end(), right.start()).compareTo(Duration.ofMinutes(5)) >= 0
          && observed(samples.subList(0, i)) >= 300
          && observed(samples.subList(i, samples.size())) >= 300) rank = Math.min(rank, 4);
      long balance =
          Math.abs(
              Duration.between(samples.getFirst().start(), left.end()).getSeconds()
                  - Duration.between(right.start(), samples.getLast().end()).getSeconds());
      if (rank < Integer.MAX_VALUE
          && (rank < bestRank || rank == bestRank && balance < bestBalance)) {
        best = i;
        bestRank = rank;
        bestBalance = balance;
      }
    }
    if (best < 0) {
      result.add(List.copyOf(atoms));
      return;
    }
    splitAtMeaningfulBoundary(atoms.subList(0, best), result);
    splitAtMeaningfulBoundary(atoms.subList(best, atoms.size()), result);
  }

  /** 作業状況の把握で用いる前面キーを返す。 */
  private static String foregroundKey(Sample s) {
    return s.roles().primary().application() + "/" + s.roles().primary().service();
  }

  /** 作業状況の把握で用いる観測を返す。 */
  private static long observed(List<Sample> samples) {
    return samples.stream().mapToLong(Sample::seconds).sum();
  }

  /** 作業状況の把握において、安定した変更に該当するか判定する。 */
  private static boolean stableChange(
      List<Sample> samples, int boundary, java.util.function.Function<Sample, String> key) {
    Instant pivot = samples.get(boundary).start();
    String before = dominant(samples.subList(0, boundary), pivot.minusSeconds(600), pivot, key);
    String after =
        dominant(samples.subList(boundary, samples.size()), pivot, pivot.plusSeconds(600), key);
    var preceding =
        samples.subList(0, boundary).reversed().stream().filter(Sample::known).findFirst();
    return !before.isEmpty()
        && !after.isEmpty()
        && !before.equals(after)
        && preceding.isPresent()
        && before.equals(key.apply(preceding.get()))
        && after.equals(key.apply(samples.get(boundary)));
  }

  /** 作業状況の把握で用いる主要な対象を返す。 */
  private static String dominant(
      List<Sample> samples,
      Instant start,
      Instant end,
      java.util.function.Function<Sample, String> key) {
    var weights = new HashMap<String, Long>();
    long total = 0;
    for (var s : samples) {
      long seconds =
          Math.max(
              0,
              Duration.between(
                      s.start().isBefore(start) ? start : s.start(),
                      s.end().isAfter(end) ? end : s.end())
                  .getSeconds());
      total += seconds;
      if (s.known()) weights.merge(key.apply(s), seconds, Long::sum);
    }
    long required = Math.max(300, (long) Math.ceil(total * .7));
    return weights.entrySet().stream()
        .filter(e -> e.getValue() >= required)
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse("");
  }

  /** 作業状況の把握において、joinsに該当するか判定する。 */
  private boolean joins(List<Context> group, Context next) {
    var first = group.getFirst().source();
    var last = group.getLast().source();
    var b = next.source();
    if (!first
        .startedAt()
        .atZone(zone)
        .toLocalDate()
        .equals(b.startedAt().atZone(zone).toLocalDate())) return false;
    if (Duration.between(first.startedAt(), b.endedAt()).compareTo(maximumWindow) > 0
        || Duration.between(last.endedAt(), b.startedAt()).compareTo(maximumGap) > 0) return false;
    var projects = new HashSet<>(next.projects());
    group.forEach(c -> projects.addAll(c.projects()));
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (projects.size() > 1) return false;
    // Unknowns do not establish a new activity; retain them inside a bounded period with an
    // explicit ratio.
    if (next.families().isEmpty() || group.stream().allMatch(c -> c.families().isEmpty()))
      return true;
    var families = new HashSet<String>();
    var labels = new HashSet<String>();
    group.forEach(
        c -> {
          families.addAll(c.families());
          labels.addAll(c.labels());
        });
    return !Collections.disjoint(families, next.families())
        || !Collections.disjoint(labels, next.labels())
        || (!projects.isEmpty() && projects.equals(next.projects()))
        || next.source().observedSeconds() <= 300;
  }

  /** 作業状況の把握で用いるfamilyを返す。 */
  private static String family(String category) {
    return switch (category) {
      case "development", "research", "documentation" -> "development-research";
      case "social", "media", "shopping" -> "web-browsing";
      case "other", "unknown" -> "unknown";
      default -> category;
    };
  }

  /** 作業状況の把握に必要な表示名評価値を扱う。 */
  private static final class LabelScore {
    /** 主要な。 */
    boolean primary;

    /** 秒数。 */
    long seconds;

    /** プロジェクト。 */
    boolean project;

    /** 表示。 */
    String display;
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  private TrendSummarySegment summarize(List<Context> group) {
    var samples =
        group.stream()
            .flatMap(c -> c.samples().stream())
            .sorted(Comparator.comparing(Sample::start))
            .toList();
    long observed = samples.stream().mapToLong(Sample::seconds).sum();
    long known = samples.stream().filter(Sample::known).mapToLong(Sample::seconds).sum();
    var knownCategories = new TreeSet<String>();
    var visibleCategories = new TreeSet<String>();
    var projects = new TreeSet<String>();
    var scores = new HashMap<String, LabelScore>();
    var continuities = new HashSet<String>();
    for (var s : samples) {
      continuities.add(s.record().continuityId());
      if (s.known()) {
        knownCategories.add(s.roles().category());
        if (!s.project().isBlank()) projects.add(s.project());
      }
      var observationLabels = new HashSet<String>();
      for (var raw : s.record().inference().activities()) {
        var a = ActivityVocabulary.canonical(raw);
        if (!Set.of("unknown", "other", "monitoring", "idle").contains(a.type()))
          visibleCategories.add(a.type());
        String label = ActivityDisplayLabels.candidate(raw);
        if (label.isBlank()) continue;
        String key = ActivityDisplayLabels.canonical(label);
        var score =
            scores.computeIfAbsent(
                key,
                k -> {
                  var value = new LabelScore();
                  value.display = label;
                  return value;
                });
        score.primary |= s.known() && a.equals(s.roles().primary());
        if (observationLabels.add(key)) score.seconds += s.seconds();
        score.project |=
            !s.project().isBlank()
                && s.project().equals(ActivityDisplayLabels.project(a, s.record()));
      }
    }
    var categories = known * 2 >= observed && known > 0 ? knownCategories : visibleCategories;
    var families = new TreeSet<String>();
    categories.forEach(c -> families.add(family(c)));
    families.remove("unknown");
    String theme =
        families.isEmpty()
            ? "unknown"
            : families.size() == 1
                ? families.getFirst()
                : families.stream()
                        .allMatch(f -> Set.of("development-research", "communication").contains(f))
                    ? "mixed-work"
                    : "mixed";
    var knownFamilies = new HashSet<String>();
    knownCategories.forEach(c -> knownFamilies.add(family(c)));
    Instant start = samples.getFirst().start(), end = samples.getLast().end();
    long missing = Math.max(0, Duration.between(start, end).getSeconds() - observed);
    long largestGap = 0;
    for (int i = 1; i < samples.size(); i++)
      largestGap =
          Math.max(
              largestGap,
              Duration.between(samples.get(i - 1).end(), samples.get(i).start()).getSeconds());
    var continuity =
        knownFamilies.size() > 1
            ? TrendSummarySegment.Continuity.MIXED
            : known == observed
                    && continuities.size() == 1
                    && largestGap <= 120
                    && missing <= Duration.between(start, end).getSeconds() * .1
                ? TrendSummarySegment.Continuity.CONTINUOUS
                : TrendSummarySegment.Continuity.INTERMITTENT;
    var labels =
        scores.entrySet().stream()
            .sorted(
                Comparator.<Map.Entry<String, LabelScore>, Boolean>comparing(
                        e -> e.getValue().project)
                    .reversed()
                    .thenComparing(
                        Comparator.<Map.Entry<String, LabelScore>, Boolean>comparing(
                                e -> e.getValue().primary)
                            .reversed())
                    .thenComparing(
                        Comparator.<Map.Entry<String, LabelScore>>comparingLong(
                                e -> e.getValue().seconds)
                            .reversed())
                    .thenComparing(
                        Comparator.<Map.Entry<String, LabelScore>, Boolean>comparing(
                                e -> e.getValue().project)
                            .reversed())
                    .thenComparing(Map.Entry::getKey))
            .limit(4)
            .map(e -> e.getValue().display)
            .toList();
    return new TrendSummarySegment(
        start,
        end,
        continuity,
        theme,
        observed,
        known,
        observed - known,
        List.copyOf(categories),
        List.copyOf(projects),
        labels,
        group.stream().map(Context::source).distinct().toList(),
        samples.stream().map(Sample::record).distinct().toList(),
        group.stream().flatMap(c -> c.source().fineSessionIds().stream()).distinct().toList());
  }
}
