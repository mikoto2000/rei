package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.ActivityDisplayLabels;
import dev.mikoto2000.rei.activity.ActivityRecord;
import dev.mikoto2000.rei.activity.ActivityRolePolicy;
import dev.mikoto2000.rei.activity.ActivitySession;
import dev.mikoto2000.rei.activity.EntertainmentDisposition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作業習慣の結果を基準に照らして評価する。
 *
 * <p>Pure evaluation of fine-session evidence. Summary projections and external context are not
 * inputs.
 */
public final class BehaviorEvaluator {
  /** 設定。 */
  private final java.util.function.Supplier<EvaluationSettings> settings;

  private record EvaluationSettings(
      Set<String> entertainmentCategories,
      long historySeconds,
      long workResetSeconds,
      long noiseToleranceSeconds,
      long noticeSeconds,
      long warningSeconds,
      long strongWarningSeconds,
      WindowLimits shortWindow,
      WindowLimits longWindow) {
    private static EvaluationSettings from(BehaviorProperties properties) {
      var thresholds = properties.getContinuous();
      var windows = properties.getWindows();
      var interruption = properties.getInterruption();
      return new EvaluationSettings(
          Set.copyOf(properties.getEntertainmentCategories()),
          properties.getHistoryMinutes() * 60L,
          interruption.getResetAfterWorkMinutes() * 60L,
          interruption.getNoiseToleranceSeconds(),
          thresholds.getNoticeMinutes() * 60L,
          thresholds.getWarningMinutes() * 60L,
          thresholds.getStrongWarningMinutes() * 60L,
          WindowLimits.from(windows.getShortWindow()),
          WindowLimits.from(windows.getLongWindow()));
    }

    private BehaviorSeverity continuousSeverity(long seconds) {
      if (seconds >= strongWarningSeconds) return BehaviorSeverity.STRONG_WARNING;
      if (seconds >= warningSeconds) return BehaviorSeverity.WARNING;
      return seconds >= noticeSeconds ? BehaviorSeverity.NOTICE : BehaviorSeverity.NONE;
    }
  }

  private record WindowLimits(int durationMinutes, long minimumObservedSeconds, double ratio) {
    private static WindowLimits from(BehaviorProperties.Window window) {
      return new WindowLimits(
          window.getDurationMinutes(), window.getMinimumObservedMinutes() * 60L, window.getRatio());
    }
  }

  /** 役割一覧。 */
  private final ActivityRolePolicy roles;

  /**
   * 作業習慣の支援で受け渡すサンプルを表す。
   *
   * @param start 開始
   * @param end 終了
   * @param category 分類
   * @param service サービス
   * @param confidence 確信度
   * @param continuity 継続性
   * @param entertainmentCategory 娯楽として分類されたか
   */
  private record Sample(
      Instant start,
      Instant end,
      String category,
      String service,
      double confidence,
      String continuity,
      boolean entertainmentCategory) {
    /** 作業習慣の支援で用いる秒数を返す。 */
    long seconds() {
      return Duration.between(start, end).getSeconds();
    }

    /** 作業習慣の支援において、対象の条件に該当するか判定する。 */
    boolean eligible() {
      return !Set.of("unknown", "other", "idle").contains(category);
    }

    boolean entertainment() {
      return eligible() && entertainmentCategory;
    }
  }

  /** 指定された値や利用先を使って、BehaviorEvaluatorを初期化する。 */
  public BehaviorEvaluator(BehaviorProperties config, double minimumConfidence) {
    config.validate();
    this.settings = () -> EvaluationSettings.from(config);
    roles = new ActivityRolePolicy(minimumConfidence);
  }

  /** 対象を判断基準に照らして評価する。 */
  public BehaviorAssessment evaluate(
      List<ActivitySession> sessions, List<ActivityRecord> records, Instant now) {
    var settings = this.settings.get();
    var samples = samples(sessions, records, now, settings);
    long continuous = 0, interruption = 0, noise = 0;
    Instant recovery = null, episode = null;
    Sample previous = null;
    long resetSeconds = settings.workResetSeconds();
    long tolerance = settings.noiseToleranceSeconds();
    boolean recoveredDuringInterruption = false;
    for (var sample : samples) {
      if (previous != null) {
        long gap = Math.max(0, Duration.between(previous.end(), sample.start()).getSeconds());
        noise += gap;
        if (noise > tolerance || !sample.continuity().equals(previous.continuity())) {
          continuous = 0;
          interruption = 0;
          recoveredDuringInterruption = false;
        }
      }
      if (sample.entertainment()) {
        continuous += sample.seconds();
        interruption = 0;
        noise = 0;
        recoveredDuringInterruption = false;
        if (episode == null) episode = sample.start();
      } else if (sample.eligible()) {
        noise = 0;
        long prior = interruption;
        interruption += sample.seconds();
        if (interruption >= resetSeconds) {
          continuous = 0;
          episode = null;
          if (!recoveredDuringInterruption) {
            recovery = sample.start().plusSeconds(Math.max(0, resetSeconds - prior));
            recoveredDuringInterruption = true;
          }
        }
      } else {
        noise += sample.seconds();
        if (noise > tolerance) {
          continuous = 0;
          interruption = 0;
          recoveredDuringInterruption = false;
        }
      }
      previous = sample;
    }
    Instant latest = previous == null ? null : previous.end();
    boolean active =
        previous != null
            && previous.entertainment()
            && Duration.between(latest, now).getSeconds() <= tolerance;
    if (latest != null && Duration.between(latest, now).getSeconds() > tolerance) continuous = 0;
    var shortWindow = window(samples, now, settings.shortWindow(), BehaviorSeverity.NOTICE, null);
    var longWindow = window(samples, now, settings.longWindow(), BehaviorSeverity.WARNING, null);
    var continuousSeverity = settings.continuousSeverity(continuous);
    var windowSeverity = BehaviorSeverity.max(shortWindow.severity(), longWindow.severity());
    var severity = BehaviorSeverity.max(continuousSeverity, windowSeverity);
    var reason =
        continuousSeverity != BehaviorSeverity.NONE && windowSeverity != BehaviorSeverity.NONE
            ? BehaviorAssessment.Reason.BOTH
            : continuousSeverity != BehaviorSeverity.NONE
                ? BehaviorAssessment.Reason.CONTINUOUS_ENTERTAINMENT
                : windowSeverity != BehaviorSeverity.NONE
                    ? BehaviorAssessment.Reason.ENTERTAINMENT_RATIO_HIGH
                    : BehaviorAssessment.Reason.NONE;
    boolean qualified =
        continuousSeverity != BehaviorSeverity.NONE
            || window(samples, now, settings.shortWindow(), BehaviorSeverity.NOTICE, recovery)
                    .severity()
                != BehaviorSeverity.NONE
            || window(samples, now, settings.longWindow(), BehaviorSeverity.WARNING, recovery)
                    .severity()
                != BehaviorSeverity.NONE;
    var categories = new HashMap<String, Long>();
    var services = new HashMap<String, Long>();
    double weighted = 0;
    long eligible = 0;
    Instant contextStart = now.minusSeconds(settings.longWindow().durationMinutes() * 60L);
    for (var sample : samples) {
      long seconds = overlap(sample, contextStart, now);
      if (sample.entertainment()) {
        eligible += seconds;
        weighted += sample.confidence() * seconds;
        categories.merge(sample.category(), seconds, Long::sum);
        if (!sample.service().isBlank()) services.merge(sample.service(), seconds, Long::sum);
      }
    }
    double confidence = eligible == 0 ? 0 : weighted / eligible;
    return new BehaviorAssessment(
        severity,
        reason,
        now,
        continuous,
        List.of(shortWindow, longWindow),
        rank(categories),
        rank(services),
        confidence,
        latest,
        active,
        recovery,
        episode,
        qualified);
  }

  /** 作業習慣の支援で用いるサンプル一覧を返す。 */
  private List<Sample> samples(
      List<ActivitySession> sessions,
      List<ActivityRecord> records,
      Instant now,
      EvaluationSettings settings) {
    var membership = new HashMap<String, List<ActivitySession>>();
    for (var session : sessions)
      for (String id : session.recordIds())
        membership.computeIfAbsent(id, k -> new ArrayList<>()).add(session);
    var sorted =
        records.stream()
            .distinct()
            .sorted(
                Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id))
            .toList();
    var result = new ArrayList<Sample>();
    Instant covered = now.minusSeconds(settings.historySeconds());
    var remaining = new HashMap<String, Long>();
    for (int i = 0; i < sorted.size(); i++) {
      var r = sorted.get(i);
      var role = roles.classify(r);
      for (var session :
          membership.getOrDefault(r.id(), List.of()).stream()
              .sorted(Comparator.comparing(ActivitySession::startedAt))
              .toList()) {
        Instant start = max(max(r.capturedAt(), session.startedAt()), covered);
        Instant end =
            min(min(r.capturedAt().plusSeconds(r.durationEstimate()), session.endedAt()), now);
        if (i + 1 < sorted.size()) end = min(end, sorted.get(i + 1).capturedAt());
        long budget = remaining.getOrDefault(session.id(), Math.max(0, session.observedSeconds()));
        end = min(end, start.plusSeconds(budget));
        if (!start.isBefore(end)) continue;
        String service =
            role.primary() == null ? "" : ActivityDisplayLabels.label(role.primary().service());
        var diagnostics = r.detection() == null ? null : r.detection().diagnostics();
        var disposition = diagnostics == null ? null : diagnostics.entertainmentDisposition();
        boolean entertainmentCategory =
            disposition == null
                ? settings.entertainmentCategories().contains(role.category())
                : disposition == EntertainmentDisposition.ENTERTAINMENT;
        result.add(
            new Sample(
                start,
                end,
                role.category(),
                service,
                role.confidence(),
                r.continuityId(),
                entertainmentCategory));
        covered = end;
        remaining.put(session.id(), budget - Duration.between(start, end).getSeconds());
      }
    }
    return result;
  }

  /** 作業習慣の支援で用いる範囲を返す。 */
  private BehaviorAssessment.Window window(
      List<Sample> samples,
      Instant now,
      WindowLimits window,
      BehaviorSeverity severity,
      Instant floor) {
    Instant start = now.minusSeconds(window.durationMinutes() * 60L);
    if (floor != null) start = max(start, floor);
    long observed = 0, eligible = 0, entertainment = 0;
    for (var s : samples) {
      long seconds = overlap(s, start, now);
      observed += seconds;
      if (s.eligible()) {
        eligible += seconds;
        if (s.entertainment()) entertainment += seconds;
      }
    }
    double ratio = eligible == 0 ? 0 : (double) entertainment / eligible;
    return new BehaviorAssessment.Window(
        window.durationMinutes(),
        entertainment,
        eligible,
        ratio,
        eligible >= window.minimumObservedSeconds() && ratio >= window.ratio()
            ? severity
            : BehaviorSeverity.NONE,
        observed);
  }

  /** 作業習慣の支援で用いる順位を返す。 */
  private static List<String> rank(Map<String, Long> weights) {
    return weights.entrySet().stream()
        .filter(e -> e.getValue() > 0)
        .sorted(
            Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
        .limit(4)
        .map(Map.Entry::getKey)
        .toList();
  }

  /** 作業習慣の支援で用いるoverlapを返す。 */
  private static long overlap(Sample s, Instant start, Instant end) {
    return Math.max(0, Duration.between(max(s.start(), start), min(s.end(), end)).getSeconds());
  }

  /** 作業習慣の支援で用いる最小を返す。 */
  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  /** 作業習慣の支援で用いる最大を返す。 */
  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }
}
