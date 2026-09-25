package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.activity.behavior.*;

class ActivityTimelinePresentationTest {
  static final Instant START=ActivitySemanticTest.START;
  BehaviorTimelineEvent event(int minute,BehaviorSeverity severity,BehaviorTimelineEvent.Outcome outcome) {
    return new BehaviorTimelineEvent("e"+minute,START.plusSeconds(minute*60),severity,BehaviorAssessment.Reason.CONTINUOUS_ENTERTAINMENT,outcome,
        outcome==BehaviorTimelineEvent.Outcome.SUPPRESSED?"COOLDOWN":"DELIVERED",3600,List.of(new BehaviorAssessment.Window(60,3600,4800,.75,severity,4800)));
  }
  ActivityRecord activity(int minute,String project) {
    var r=ActivitySemanticTest.record(minute,"Terminal",project,ActivitySemanticTest.activity("coding","Terminal","",project));
    return new ActivityRecord(r.id(),r.capturedAt(),1200,r.observations(),r.foreground(),r.inference(),r.confidence(),List.of(),0,false);
  }
  @Test void intervalsAndInstantsMergeInTimestampOrderAndVerboseAddsSuppression() {
    var store=mock(ActivityStore.class);var behavior=mock(BehaviorStateStore.class);
    when(store.findRecordsBetween(any(),any())).thenReturn(List.of(activity(0,"alpha"),activity(20,"beta")));
    when(behavior.findEventsBetween(any(),any())).thenReturn(List.of(event(35,BehaviorSeverity.WARNING,BehaviorTimelineEvent.Outcome.EMITTED),
        event(15,BehaviorSeverity.NOTICE,BehaviorTimelineEvent.Outcome.EMITTED),event(27,BehaviorSeverity.STRONG_WARNING,BehaviorTimelineEvent.Outcome.SUPPRESSED),event(1,BehaviorSeverity.NONE,BehaviorTimelineEvent.Outcome.SUPPRESSED)));
    var timeline=new ActivityTimeline(store,Clock.fixed(START,ZoneOffset.UTC));var service=new ActivityTimelinePresentationService(timeline,behavior);
    var text=service.format("today",false);
    assertTrue(text.indexOf("alpha")<text.indexOf("09:23 Behavior NOTICE"),text);
    assertTrue(text.indexOf("09:23 Behavior NOTICE")<text.indexOf("beta"),text);
    assertTrue(text.indexOf("beta")<text.indexOf("09:43 Behavior WARNING"),text);
    assertFalse(text.contains("STRONG_WARNING"));assertFalse(text.contains("Behavior NONE"));assertFalse(text.contains("confidence:"));
    verify(store).findRecordsBetween(Instant.parse("2026-09-23T00:00:00Z"),Instant.parse("2026-09-24T00:00:00Z"));
    verify(behavior).findEventsBetween(Instant.parse("2026-09-23T00:00:00Z"),Instant.parse("2026-09-24T00:00:00Z"));
    verify(store,never()).findBetween(any(),any());verify(store,never()).replace(any());verify(behavior,never()).save(any(),any());
    var verbose=service.format("today",true);
    for(var value:List.of("STRONG_WARNING","SUPPRESSED","COOLDOWN","CONTINUOUS_ENTERTAINMENT","75.0%","60.0 min","confidence:"))assertTrue(verbose.contains(value),verbose);
    assertFalse(verbose.contains("Behavior NONE"));
  }
  @Test void behaviorOnlyAndSameTimestampRemainVisibleAndSummaryIsUnchanged() {
    var store=mock(ActivityStore.class);var behavior=mock(BehaviorStateStore.class);var timeline=new ActivityTimeline(store,Clock.fixed(START,ZoneId.of("Asia/Tokyo")));
    when(behavior.findEventsBetween(any(),any())).thenReturn(List.of(event(0,BehaviorSeverity.STRONG_WARNING,BehaviorTimelineEvent.Outcome.EMITTED)));
    var before=timeline.trendSummary("today");var service=new ActivityTimelinePresentationService(timeline,behavior);
    assertTrue(service.format("today",false).contains("18:08 Behavior STRONG_WARNING"));
    when(store.findRecordsBetween(any(),any())).thenReturn(List.of(activity(0,"alpha")));
    var entries=service.entries("today",false);assertInstanceOf(ActivityTimelineEntry.ActivityEntry.class,entries.get(0));assertInstanceOf(ActivityTimelineEntry.BehaviorEntry.class,entries.get(1));
    when(store.findRecordsBetween(any(),any())).thenReturn(List.of());assertEquals(before,timeline.trendSummary("today"));
  }
  @Test void evidenceChangesWithinGroupAreNotClaimedForTheEntireInterval() {
    var no=ActivityEvidenceDisplayFormatterTest.state(VisionDiagnostics.State.NOT_ATTEMPTED);var used=ActivityEvidenceDisplayFormatterTest.state(VisionDiagnostics.State.USED);
    var a=ActivityEvidenceDisplayFormatterTest.record(no,no);var original=ActivityEvidenceDisplayFormatterTest.record(used,no);
    var b=new ActivityRecord("later",a.capturedAt().plusSeconds(60),60,original.observations(),original.foreground(),original.inference(),original.confidence(),List.of(),0,false,original.continuityId(),original.detection());
    var store=mock(ActivityStore.class);when(store.findRecordsBetween(any(),any())).thenReturn(List.of(a,b));
    var service=new ActivityTimelinePresentationService(new ActivityTimeline(store,Clock.fixed(START,ZoneOffset.UTC)),null);
    assertTrue(service.format("today",false).contains("Window / Window + Foreground Vision（区間内で判定根拠が変化）"));
  }
}
