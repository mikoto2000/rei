package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 作業状況の把握に必要な作業状況品質評価を扱う。
 *
 * <p>Anonymous evaluation of existing projections. Never promotes an inference into an observed
 * fact.
 */
public final class ActivityQualityEvaluation {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ActivityQualityEvaluation() {}

  /** 作業状況の把握における起点の選択肢。 */
  public enum Origin {
    /** 観測。 */
    OBSERVED,
    /** 推定した。 */
    INFERRED,
    /** 利用者確認済み。 */
    USER_CONFIRMED
  }

  /**
   * 作業状況の把握で受け渡す確認済み事実を表す。
   *
   * @param key 照合キー
   * @param value 値
   * @param confirmationId 確認識別子
   */
  public record ConfirmedFact(String key, String value, String confirmationId) {
    /** 受け取った情報からConfirmedFactを作成する。 */
    public ConfirmedFact {
      bounded(key, 128);
      bounded(value, 1024);
      bounded(confirmationId, 128);
    }
  }

  /**
   * 作業状況の把握で受け渡す事実を表す。
   *
   * @param key 照合キー
   * @param value 値
   * @param origin 起点
   * @param evidenceId 根拠識別子
   */
  public record Fact(String key, String value, Origin origin, String evidenceId) {}

  /**
   * 作業状況の把握で受け渡す事例を表す。
   *
   * @param id 識別子
   * @param quality 品質
   * @param process プロセス
   * @param title タイトル
   * @param confidence 確信度
   * @param observedSeconds 観測秒数
   * @param candidates 候補一覧
   * @param expectedProject 期待する内容プロジェクト
   * @param expectedTheme 期待する内容テーマ
   * @param expectedAdvice 期待する内容助言
   * @param expectedReason 期待する内容理由
   * @param confirmedFacts 確認済み事実一覧
   */
  public record Case(
      String id,
      String quality,
      String process,
      String title,
      double confidence,
      long observedSeconds,
      List<ActivityRecord.Activity> candidates,
      String expectedProject,
      String expectedTheme,
      boolean expectedAdvice,
      String expectedReason,
      List<ConfirmedFact> confirmedFacts) {
    /** 受け取った情報からCaseを作成する。 */
    public Case {
      bounded(id, 128);
      bounded(quality, 128);
      bounded(process, 128);
      if (title == null || title.length() > 1024)
        throw new IllegalArgumentException("Bounded title required");
      if (!Double.isFinite(confidence)
          || confidence < 0
          || confidence > 1
          || observedSeconds < 0
          || observedSeconds > 86400)
        throw new IllegalArgumentException("Bounded observation required");
      candidates = List.copyOf(candidates);
      confirmedFacts = List.copyOf(confirmedFacts);
      if (candidates.size() > 16 || confirmedFacts.size() > 16)
        throw new IllegalArgumentException("Bounded candidates/facts required");
      for (var candidate : candidates) {
        bounded(candidate.type(), 128);
        bounded(candidate.application(), 128);
        if (candidate.projectCandidate() == null
            || candidate.projectCandidate().length() > 128
            || candidate.contentTitle() == null
            || candidate.contentTitle().length() > 1024
            || candidate.service() == null
            || candidate.service().length() > 128)
          throw new IllegalArgumentException("Bounded candidate required");
      }
      if (expectedProject == null || expectedProject.length() > 128)
        throw new IllegalArgumentException("Expected project required");
      bounded(expectedTheme, 128);
      bounded(expectedReason, 128);
    }
  }

  /**
   * 作業状況の把握で受け渡す行を表す。
   *
   * @param id 識別子
   * @param quality 品質
   * @param project プロジェクト
   * @param theme テーマ
   * @param projectMatches プロジェクト一致結果
   * @param themeMatches テーマ一致結果
   * @param observedSeconds 観測秒数
   * @param unobservedSeconds 未観測秒数
   * @param coverage 対象範囲
   * @param advice 助言
   * @param reason 理由
   * @param facts 事実一覧
   * @param violations 満たしていない条件
   */
  public record Row(
      String id,
      String quality,
      String project,
      String theme,
      boolean projectMatches,
      boolean themeMatches,
      long observedSeconds,
      long unobservedSeconds,
      double coverage,
      boolean advice,
      String reason,
      List<Fact> facts,
      List<String> violations) {}

  /**
   * 作業状況の把握で受け渡すレポートを表す。
   *
   * @param provider 提供元
   * @param rows 行一覧
   * @param projectAccuracy プロジェクト正確さ
   * @param themeAccuracy テーマ正確さ
   * @param structuralFailures structural失敗一覧
   * @param truthVerified 確定情報確認済み
   */
  public record Report(
      String provider,
      List<Row> rows,
      double projectAccuracy,
      double themeAccuracy,
      int structuralFailures,
      boolean truthVerified) {}

  /** 対象を判断基準に照らして評価する。 */
  public static Report evaluate(List<Case> fixtures) {
    fixtures = List.copyOf(fixtures);
    if (fixtures.isEmpty()
        || fixtures.size() > 128
        || fixtures.stream().map(Case::id).distinct().count() != fixtures.size())
      throw new IllegalArgumentException("Unique bounded fixtures required");
    var start = Instant.parse("2026-09-21T00:00:00Z");
    var clock = Clock.fixed(start.plusSeconds(1209600), ZoneOffset.UTC);
    var analysis = new ActivityPeriodAnalysis(clock, .5);
    var range = analysis.range(ActivityPeriodAnalysis.Period.WEEK, LocalDate.of(2026, 9, 21));
    var previousRange = analysis.previous(range);
    var settings = new PeriodCoaching.Settings(true, Set.of("development"), .9, 60, .005, .25, 7);
    var rows = new ArrayList<Row>();
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    for (var fixture : fixtures) {
      RunCancellation.propagate(null);
      if (System.nanoTime() > deadline)
        throw new IllegalStateException("Activity evaluation deadline exceeded");
      var record =
          new ActivityRecord(
              fixture.id(),
              start.plusSeconds(3600),
              fixture.observedSeconds(),
              List.of(),
              new ForegroundWindow(fixture.process(), 1, fixture.title(), "anonymous-window"),
              new ActivityRecord.Inference("anonymous candidates", fixture.candidates()),
              fixture.confidence(),
              List.of(),
              0,
              false,
              "anonymous");
      var roles = new ActivityRolePolicy().classify(record);
      String project = roles.primary() == null ? "" : roles.primary().projectCandidate();
      String theme = SummaryGroupingPolicy.theme(roles);
      var current = analysis.aggregate(range, List.of(record));
      var priorRecord =
          new ActivityRecord(
              "prior",
              previousRange.fromInclusive().plusSeconds(3600),
              7200,
              List.of(),
              new ForegroundWindow("Terminal", 1, "project-alpha", "prior-window"),
              new ActivityRecord.Inference(
                  "anonymous baseline",
                  List.of(
                      new ActivityRecord.Activity(
                          "m1", "development", "Terminal", "", "", "project-alpha"))),
              .9,
              List.of(),
              0,
              false,
              "prior");
      var previous = analysis.aggregate(previousRange, List.of(priorRecord));
      var assessment = new PeriodCoaching().evaluate(settings, current, previous);
      var violations = new ArrayList<String>();
      if (assessment.advice() != fixture.expectedAdvice()) violations.add("ADVICE_EXPECTATION");
      if (!assessment.reason().equals(fixture.expectedReason()))
        violations.add("REASON_EXPECTATION");
      long span = current.observedSeconds() + current.unobservedSeconds();
      double coverage = span == 0 ? 0 : (double) current.observedSeconds() / span;
      if (assessment.advice()
          && (coverage < settings.minimumCoverage()
              || current.observedSeconds() < settings.minimumObservedMinutes() * 60L
              || roles.primary() == null)) violations.add("UNGROUNDED_ADVICE");
      if (!Double.isFinite(assessment.currentShare())
          || assessment.currentShare() < 0
          || assessment.currentShare() > 1) violations.add("INVALID_SHARE");
      var facts = new ArrayList<Fact>();
      facts.add(new Fact("foregroundProcess", fixture.process(), Origin.OBSERVED, record.id()));
      facts.add(new Fact("foregroundTitle", fixture.title(), Origin.OBSERVED, record.id()));
      facts.add(new Fact("projectCandidate", project, Origin.INFERRED, record.id()));
      facts.add(new Fact("themeCandidate", theme, Origin.INFERRED, record.id()));
      facts.add(
          new Fact(
              "observedDurationEstimateSeconds",
              Long.toString(current.observedSeconds()),
              Origin.INFERRED,
              record.id()));
      for (var confirmed : fixture.confirmedFacts())
        facts.add(
            new Fact(
                confirmed.key(),
                confirmed.value(),
                Origin.USER_CONFIRMED,
                confirmed.confirmationId()));
      rows.add(
          new Row(
              fixture.id(),
              fixture.quality(),
              project,
              theme,
              project.equals(fixture.expectedProject()),
              theme.equals(fixture.expectedTheme()),
              current.observedSeconds(),
              current.unobservedSeconds(),
              coverage,
              assessment.advice(),
              assessment.reason(),
              List.copyOf(facts),
              List.copyOf(violations)));
    }
    return new Report(
        "ActivityRolePolicy/ActivityPeriodAnalysis/PeriodCoaching",
        List.copyOf(rows),
        (double) rows.stream().filter(Row::projectMatches).count() / rows.size(),
        (double) rows.stream().filter(Row::themeMatches).count() / rows.size(),
        rows.stream().mapToInt(row -> row.violations().size()).sum(),
        false);
  }

  /**
   * 作業状況の把握で受け渡すデスクトップ行を表す。
   *
   * @param mode 動作モード
   * @param observedSeconds 観測秒数
   * @param contextCandidates 文脈候補一覧
   * @param primaryChanges 主要な変更一覧
   * @param visionCalls 画像認識呼び出し回数
   * @param queueWaitMillis 実行待ち待機ミリ秒数
   * @param processingMillis processingミリ秒数
   * @param freshnessMillis 情報の鮮度ミリ秒数
   * @param temporalInference 時系列推定
   */
  public record DesktopRow(
      String mode,
      long observedSeconds,
      int contextCandidates,
      int primaryChanges,
      long visionCalls,
      long queueWaitMillis,
      long processingMillis,
      long freshnessMillis,
      String temporalInference) {}

  /**
   * 作業状況の把握で受け渡すデスクトップレポートを表す。
   *
   * @param rows 行一覧
   * @param truthVerified 確定情報確認済み
   * @param limitation 制約
   */
  public record DesktopReport(List<DesktopRow> rows, boolean truthVerified, String limitation) {}

  /**
   * 作業状況の把握で用いる比較デスクトップを返す。
   *
   * <p>Replay already-analyzed observations, without calls to Vision or disk writes.
   */
  public static DesktopReport compareDesktop(List<ActivityRecord> records) {
    if (records.isEmpty()
        || records.size() > 120
        || records.stream().map(ActivityRecord::id).distinct().count() != records.size())
      throw new IllegalArgumentException("Bounded desktop fixtures required");
    var rows = new ArrayList<DesktopRow>();
    long duration = records.stream().mapToLong(ActivityRecord::durationEstimate).sum();
    int contexts = 0;
    long foregroundCalls = 0,
        backgroundCalls = 0,
        wait = 0,
        processing = 0,
        freshness = 0,
        foregroundWait = 0,
        foregroundProcessing = 0,
        foregroundFreshness = 0;
    for (var record : records) {
      var v = VisionDiagnostics.of(record.detection());
      for (var result : new VisionDiagnostics.Result[] {v.foreground(), v.background()})
        if (result != null
            && result.timing() != null
            && result.state() != VisionDiagnostics.State.NOT_ATTEMPTED
            && result.timing().startedAt() != null) {
          var t = result.timing();
          if (t.startedAt() != null) {
            if (result == v.foreground()) foregroundCalls++;
            else backgroundCalls++;
          }
          if (t.imageCapturedAt() != null && t.startedAt() != null) {
            long value =
                Math.max(0, Duration.between(t.imageCapturedAt(), t.startedAt()).toMillis());
            wait += value;
            if (result == v.foreground()) foregroundWait += value;
          }
          if (t.startedAt() != null && t.completedAt() != null) {
            long value = Math.max(0, Duration.between(t.startedAt(), t.completedAt()).toMillis());
            processing += value;
            if (result == v.foreground()) foregroundProcessing += value;
          }
          if (t.completedAt() != null) {
            long value =
                Math.max(0, Duration.between(record.capturedAt(), t.completedAt()).toMillis());
            freshness = Math.max(freshness, value);
            if (result == v.foreground())
              foregroundFreshness = Math.max(foregroundFreshness, value);
          }
        }
      if (v.background() != null && v.background().context() != null)
        contexts += v.background().context().candidates().size();
    }
    rows.add(
        new DesktopRow(
            "A",
            duration,
            0,
            0,
            foregroundCalls,
            foregroundWait,
            foregroundProcessing,
            foregroundFreshness,
            ""));
    rows.add(
        new DesktopRow(
            "B",
            duration,
            contexts,
            0,
            foregroundCalls + backgroundCalls,
            wait,
            processing,
            freshness,
            ""));
    var first =
        records.stream()
            .map(ActivityRecord::capturedAt)
            .min(Comparator.naturalOrder())
            .orElseThrow();
    var last =
        records.stream()
            .map(ActivityRecord::capturedAt)
            .max(Comparator.naturalOrder())
            .orElseThrow();
    rows.add(
        new DesktopRow(
            "C",
            duration,
            contexts,
            0,
            foregroundCalls + backgroundCalls,
            wait,
            processing,
            freshness,
            TemporalActivityEvidence.build(records, List.of(), first, last)
                .ruleInference()
                .inferredActivity()));
    return new DesktopReport(
        List.copyOf(rows),
        false,
        "Replay is structural evaluation. Accuracy requires independent manual labels; CPU/RSS/SSD I/O are not measured.");
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private static void bounded(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException("Bounded nonblank fixture value required");
  }
}
