package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.activity.behavior.BehaviorAssessment;
import dev.mikoto2000.rei.activity.behavior.BehaviorSeverity;
import dev.mikoto2000.rei.activity.behavior.BehaviorStateStore;
import dev.mikoto2000.rei.activity.behavior.BehaviorTimelineEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 作業状況時系列の記録表示用の情報の結果と状態の契約を検証するテスト。 */
class ActivityTimelinePresentationTest {
  /** 検証に使う開始。 */
  static final Instant START = ActivitySemanticTest.START;

  /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
  BehaviorTimelineEvent event(
      int minute, BehaviorSeverity severity, BehaviorTimelineEvent.Outcome outcome) {
    return new BehaviorTimelineEvent(
        "e" + minute,
        START.plusSeconds(minute * 60),
        severity,
        BehaviorAssessment.Reason.CONTINUOUS_ENTERTAINMENT,
        outcome,
        outcome == BehaviorTimelineEvent.Outcome.SUPPRESSED ? "COOLDOWN" : "DELIVERED",
        3600,
        List.of(new BehaviorAssessment.Window(60, 3600, 4800, .75, severity, 4800)));
  }

  /** {@code activity} の条件を再現するための補助情報を返す。 */
  ActivityRecord activity(int minute, String project) {
    var r =
        ActivitySemanticTest.record(
            minute,
            "Terminal",
            project,
            ActivitySemanticTest.activity("coding", "Terminal", "", project));
    return new ActivityRecord(
        r.id(),
        r.capturedAt(),
        1200,
        r.observations(),
        r.foreground(),
        r.inference(),
        r.confidence(),
        List.of(),
        0,
        false);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void intervalsAndInstantsMergeInTimestampOrderAndVerboseAddsSuppression() {
    var store = mock(ActivityStore.class);
    var behavior = mock(BehaviorStateStore.class);
    when(store.findRecordsBetween(any(), any()))
        .thenReturn(List.of(activity(0, "alpha"), activity(20, "beta")));
    when(behavior.findEventsBetween(any(), any()))
        .thenReturn(
            List.of(
                event(35, BehaviorSeverity.WARNING, BehaviorTimelineEvent.Outcome.EMITTED),
                event(15, BehaviorSeverity.NOTICE, BehaviorTimelineEvent.Outcome.EMITTED),
                event(
                    27, BehaviorSeverity.STRONG_WARNING, BehaviorTimelineEvent.Outcome.SUPPRESSED),
                event(1, BehaviorSeverity.NONE, BehaviorTimelineEvent.Outcome.SUPPRESSED)));
    var timeline = new ActivityTimeline(store, Clock.fixed(START, ZoneOffset.UTC));
    var service = new ActivityTimelinePresentationService(timeline, behavior);
    var text = service.format("today", false);
    assertTrue(text.indexOf("alpha") < text.indexOf("09:23 Behavior NOTICE"), text);
    assertTrue(text.indexOf("09:23 Behavior NOTICE") < text.indexOf("beta"), text);
    assertTrue(text.indexOf("beta") < text.indexOf("09:43 Behavior WARNING"), text);
    assertFalse(text.contains("STRONG_WARNING"));
    assertFalse(text.contains("Behavior NONE"));
    assertFalse(text.contains("confidence:"));
    verify(store)
        .findRecordsBetween(
            Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));
    verify(behavior)
        .findEventsBetween(
            Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));
    verify(store, never()).findBetween(any(), any());
    verify(store, never()).replace(any());
    verify(behavior, never()).save(any(), any());
    var verbose = service.format("today", true);
    for (var value :
        List.of(
            "STRONG_WARNING",
            "SUPPRESSED",
            "COOLDOWN",
            "CONTINUOUS_ENTERTAINMENT",
            "75.0%",
            "60.0 min",
            "confidence:")) assertTrue(verbose.contains(value), verbose);
    assertFalse(verbose.contains("Behavior NONE"));
  }

  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void behaviorOnlyAndSameTimestampRemainVisibleAndSummaryIsUnchanged() {
    var store = mock(ActivityStore.class);
    var behavior = mock(BehaviorStateStore.class);
    var timeline = new ActivityTimeline(store, Clock.fixed(START, ZoneId.of("Asia/Tokyo")));
    when(behavior.findEventsBetween(any(), any()))
        .thenReturn(
            List.of(
                event(0, BehaviorSeverity.STRONG_WARNING, BehaviorTimelineEvent.Outcome.EMITTED)));
    var before = timeline.trendSummary("today");
    var service = new ActivityTimelinePresentationService(timeline, behavior);
    assertTrue(service.format("today", false).contains("18:08 Behavior STRONG_WARNING"));
    when(store.findRecordsBetween(any(), any())).thenReturn(List.of(activity(0, "alpha")));
    var entries = service.entries("today", false);
    assertInstanceOf(ActivityTimelineEntry.ActivityEntry.class, entries.get(0));
    assertInstanceOf(ActivityTimelineEntry.BehaviorEntry.class, entries.get(1));
    when(store.findRecordsBetween(any(), any())).thenReturn(List.of());
    assertEquals(before, timeline.trendSummary("today"));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void evidenceChangesWithinGroupAreNotClaimedForTheEntireInterval() {
    var no = ActivityEvidenceDisplayFormatterTest.state(VisionDiagnostics.State.NOT_ATTEMPTED);
    var used = ActivityEvidenceDisplayFormatterTest.state(VisionDiagnostics.State.USED);
    var a = ActivityEvidenceDisplayFormatterTest.record(no, no);
    var original = ActivityEvidenceDisplayFormatterTest.record(used, no);
    var b =
        new ActivityRecord(
            "later",
            a.capturedAt().plusSeconds(60),
            60,
            original.observations(),
            original.foreground(),
            original.inference(),
            original.confidence(),
            List.of(),
            0,
            false,
            original.continuityId(),
            original.detection());
    var store = mock(ActivityStore.class);
    when(store.findRecordsBetween(any(), any())).thenReturn(List.of(a, b));
    var service =
        new ActivityTimelinePresentationService(
            new ActivityTimeline(store, Clock.fixed(START, ZoneOffset.UTC)), null);
    assertTrue(
        service
            .format("today", false)
            .contains("Window / Window + Foreground Vision（区間内で判定根拠が変化）"));
  }
}
