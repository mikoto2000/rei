package dev.mikoto2000.rei.activity.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.activity.ActivityRecord;
import dev.mikoto2000.rei.activity.ActivitySession;
import dev.mikoto2000.rei.activity.ForegroundWindow;
import dev.mikoto2000.rei.activity.SessionMergePolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 作業習慣評価の判断の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BehaviorEvaluatorTest {
  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void successfulObservationIncludesExcludedCategoriesButNotMissingTime() {
    var a =
        assess(
            3600,
            record(0, 600, "social"),
            record(600, 600, "unknown"),
            record(1800, 600, "idle"),
            record(2400, 600, "other"));
    var w = a.windows().getFirst();
    assertEquals(2400, w.observedSeconds());
    assertEquals(600, w.eligibleObservedSeconds());
    assertEquals(600, w.entertainmentObservedSeconds());
    assertEquals(1, w.entertainmentRatio());
    assertEquals(BehaviorSeverity.NONE, a.severity());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void successfulObservationIsClippedToWindowAndDoesNotDoubleCountOverlap() {
    var a = assess(7200, record(0, 5400, "unknown"), record(3600, 3600, "social"));
    assertEquals(3600, a.windows().getFirst().observedSeconds());
    assertEquals(7200, a.windows().getLast().observedSeconds());
  }

  /** 検証に使う開始。 */
  static final Instant START = Instant.parse("2026-09-23T09:00:00Z");

  /** 検証用に、後から経緯を確認できるよう、指定された内容を記録する。 */
  static ActivityRecord record(int startSeconds, int seconds, String category) {
    String service =
        switch (category) {
          case "social" -> "X";
          case "media" -> "YouTube";
          case "shopping" -> "Amazon";
          default -> "";
        };
    var a = new ActivityRecord.Activity("m1", category, "Firefox", service, "observed context", "");
    return new ActivityRecord(
        "r" + startSeconds,
        START.plusSeconds(startSeconds),
        seconds,
        List.of(),
        new ForegroundWindow("Firefox", 1, service + " observed context", "window"),
        new ActivityRecord.Inference("raw", List.of(a)),
        .9,
        List.of(),
        .1,
        false,
        "run");
  }

  /** {@code sessions} の条件を再現するための補助情報を返す。 */
  static List<ActivitySession> sessions(List<ActivityRecord> records) {
    return new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC).aggregate(records);
  }

  /** {@code assess} の条件を再現するための補助情報を返す。 */
  static BehaviorAssessment assess(int now, ActivityRecord... records) {
    var list = List.of(records);
    return new BehaviorEvaluator(new BehaviorProperties(), .5)
        .evaluate(sessions(list), list, START.plusSeconds(now));
  }

  /** 継続判定の境界値について、観測時刻を利用することを確認する。 */
  @Test
  void continuousThresholdsUseObservedTime() {
    for (var entry :
        Map.of(
                29,
                BehaviorSeverity.NONE,
                30,
                BehaviorSeverity.NOTICE,
                60,
                BehaviorSeverity.WARNING,
                120,
                BehaviorSeverity.STRONG_WARNING)
            .entrySet())
      assertEquals(
          entry.getValue(),
          assess(entry.getKey() * 60, record(0, entry.getKey() * 60, "social")).severity());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void entertainmentSwitchesDoNotReset() {
    var a =
        assess(
            3600,
            record(0, 1200, "social"),
            record(1200, 1200, "media"),
            record(2400, 1200, "gaming"));
    assertEquals(3600, a.continuousEntertainmentSeconds());
    assertEquals(BehaviorSeverity.WARNING, a.severity());
    assertTrue(a.dominantCategories().contains("gaming"));
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void nonWorkIsNotAutomaticallyEntertainment() {
    for (String c :
        List.of(
            "development",
            "research",
            "documentation",
            "communication",
            "monitoring",
            "navigation",
            "idle",
            "other",
            "unknown")) {
      var a = assess(7200, record(0, 7200, c));
      assertEquals(BehaviorSeverity.NONE, a.severity(), c);
      assertEquals(0, a.windows().getFirst().entertainmentObservedSeconds(), c);
    }
  }

  /** 作業習慣の支援で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void sustainedWorkResetsContinuousAndMarksRecovery() {
    var a =
        assess(
            4500,
            record(0, 2100, "social"),
            record(2100, 600, "development"),
            record(2700, 1800, "social"));
    assertEquals(1800, a.continuousEntertainmentSeconds());
    assertEquals(START.plusSeconds(2400), a.recoveredAt());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void shortUnknownAndMonitoringAreNoiseNotEntertainment() {
    for (String noise : List.of("unknown", "monitoring")) {
      var a =
          assess(
              3030,
              record(0, 1800, "social"),
              record(1800, 30, noise),
              record(1830, 1200, "media"));
      assertEquals(3000, a.continuousEntertainmentSeconds());
    }
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void largeUnobservedGapBreaksContinuityAndDoesNotDiluteRatio() {
    var a = assess(3600, record(0, 600, "social"), record(3000, 600, "social"));
    assertEquals(600, a.continuousEntertainmentSeconds());
    assertEquals(1200, a.windows().getFirst().eligibleObservedSeconds());
    assertEquals(1, a.windows().getFirst().entertainmentRatio());
    assertEquals(BehaviorSeverity.NONE, a.severity());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownOtherIdleExcludedFromDenominatorAndMinimumObservationProtectsRatio() {
    for (String c : List.of("unknown", "other", "idle")) {
      var a = assess(3600, record(0, 1200, "social"), record(1200, 2400, c));
      assertEquals(1200, a.windows().getFirst().eligibleObservedSeconds());
      assertEquals(BehaviorSeverity.NONE, a.severity());
    }
  }

  /** 短い範囲について、対象の条件割合を利用することを確認する。 */
  @Test
  void shortWindowUsesEligibleRatio() {
    var a = assess(2400, record(0, 1200, "development"), record(1200, 1200, "social"));
    assertEquals(.5, a.windows().getFirst().entertainmentRatio());
    assertEquals(BehaviorSeverity.NOTICE, a.severity());
    assertEquals(BehaviorAssessment.Reason.ENTERTAINMENT_RATIO_HIGH, a.reason());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void longWindowWinsOverContinuousNotice() {
    var a =
        assess(
            6000,
            record(0, 2400, "social"),
            record(2400, 2400, "development"),
            record(4800, 1200, "media"));
    assertEquals(.6, a.windows().getLast().entertainmentRatio());
    assertEquals(BehaviorSeverity.WARNING, a.severity());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void insufficientObservationDoesNotTriggerRatio() {
    assertEquals(BehaviorSeverity.NONE, assess(600, record(0, 600, "media")).severity());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void windowClipsIntervalsAndOverlappingSamplesAreNotDoubleCounted() {
    var a = assess(7200, record(0, 5400, "social"), record(3600, 3600, "development"));
    assertEquals(0, a.windows().getFirst().entertainmentObservedSeconds());
    assertEquals(3600, a.windows().getFirst().eligibleObservedSeconds());
    assertEquals(7200, a.windows().getLast().eligibleObservedSeconds());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void secondaryVideoIsNotPrimaryEntertainment() {
    var r = record(0, 3600, "development");
    var activities = new ArrayList<>(r.inference().activities());
    activities.add(
        new ActivityRecord.Activity("m2", "media", "OtherBrowser", "YouTube", "video", ""));
    r =
        new ActivityRecord(
            r.id(),
            r.capturedAt(),
            r.durationEstimate(),
            r.observations(),
            r.foreground(),
            new ActivityRecord.Inference("raw", activities),
            r.confidence(),
            List.of(),
            .1,
            false,
            "run");
    assertEquals(BehaviorSeverity.NONE, assess(3600, r).severity());
  }

  /** 作業習慣の支援で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void fineSessionMembershipIsRequiredAndEvidenceIsUntouched() {
    var r = record(0, 3600, "social");
    var evaluator = new BehaviorEvaluator(new BehaviorProperties(), .5);
    assertEquals(
        BehaviorSeverity.NONE,
        evaluator.evaluate(List.of(), List.of(r), START.plusSeconds(3600)).severity());
    assertEquals(r, record(0, 3600, "social"));
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void continuityGenerationAndStaleDataCannotLookCurrentlyContinuous() {
    var a = record(0, 1800, "social");
    var b = record(1800, 1800, "media");
    b =
        new ActivityRecord(
            b.id(),
            b.capturedAt(),
            b.durationEstimate(),
            b.observations(),
            b.foreground(),
            b.inference(),
            b.confidence(),
            List.of(),
            .1,
            false,
            "resumed");
    assertEquals(1800, assess(3600, a, b).continuousEntertainmentSeconds());
    assertFalse(assess(4000, a, b).activeEntertainment());
  }

  /** 作業習慣の支援で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void recoveryDoesNotLetOldWindowImmediatelyTriggerNewEpisode() {
    var a =
        assess(
            3960,
            record(0, 3600, "social"),
            record(3600, 300, "development"),
            record(3900, 60, "social"));
    assertEquals(BehaviorSeverity.WARNING, a.severity());
    assertFalse(a.episodeQualified());
  }

  /** 作業習慣の支援で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void defaultsAndInvalidConfig() {
    var p = new BehaviorProperties();
    assertFalse(p.isEnabled());
    assertEquals(Set.of("social", "media", "shopping", "gaming"), p.getEntertainmentCategories());
    p.validate();
    p.getWindows().getShortWindow().setMinimumObservedMinutes(61);
    assertThrows(IllegalArgumentException.class, p::validate);
  }

  /** 作業習慣の支援で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void fineObservedBudgetIsAnUpperBoundEvenWithIncompleteEvidence() {
    var r = record(0, 3600, "social");
    var source = sessions(List.of(r)).getFirst();
    var fine =
        new ActivitySession(
            source.id(),
            source.startedAt(),
            source.endedAt(),
            1200,
            source.recordIds(),
            source.inference(),
            source.primaryApplication(),
            source.confidence());
    var a =
        new BehaviorEvaluator(new BehaviorProperties(), .5)
            .evaluate(List.of(fine), List.of(r), START.plusSeconds(3600));
    assertEquals(1200, a.windows().getFirst().entertainmentObservedSeconds());
    assertEquals(BehaviorSeverity.NONE, a.severity());
  }

  /** 作業習慣の支援で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void workAfterLongUnknownStillRecovers() {
    var a =
        assess(
            3300,
            record(0, 1800, "social"),
            record(1800, 1200, "unknown"),
            record(3000, 300, "development"));
    assertEquals(START.plusSeconds(3300), a.recoveredAt());
    assertEquals(0, a.continuousEntertainmentSeconds());
  }

  /** 作業習慣の支援で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void strongWorkConfidenceDoesNotInflateWeakEntertainmentConfidence() {
    var social = record(1200, 1800, "social");
    social =
        new ActivityRecord(
            social.id(),
            social.capturedAt(),
            1800,
            social.observations(),
            social.foreground(),
            social.inference(),
            .6,
            List.of(),
            .1,
            false,
            "run");
    assertTrue(assess(3000, record(0, 1200, "development"), social).confidence() < .7);
  }

  /** 不明間隔を保持することを確認する。 */
  @Test
  void mixedTimelineFixturePreservesUnknownGapAndWorkBoundary() throws Exception {
    var records =
        List.of(
            record(0, 1200, "social"),
            record(1200, 600, "media"),
            record(1800, 300, "development"),
            record(2100, 30, "unknown"),
            record(2430, 600, "social"),
            record(3030, 900, "media"),
            record(3930, 600, "development"),
            record(4530, 1800, "social"));
    var a =
        new BehaviorEvaluator(new BehaviorProperties(), .5)
            .evaluate(sessions(records), records, START.plusSeconds(6330));
    assertEquals(1800, a.continuousEntertainmentSeconds());
    assertEquals(BehaviorSeverity.WARNING, a.severity());
    assertEquals(5100, a.windows().getLast().entertainmentObservedSeconds());
    assertEquals(6000, a.windows().getLast().eligibleObservedSeconds());
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/activity35-example.txt"),
        "Synthetic SNS/YouTube/Terminal/unknown/gap fixture (not user's database):\n" + a);
  }
}
