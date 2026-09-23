package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivitySemanticTest {
  static final Instant START=Instant.parse("2026-09-23T09:08:00Z");
  static ActivityRecord.Activity activity(String type,String app,String service,String project) {
    return new ActivityRecord.Activity("m1",type,app,service,"",project);
  }
  static ActivityRecord record(int minute,String app,String title,ActivityRecord.Activity... activities) {
    return new ActivityRecord("r"+minute,START.plusSeconds(minute*60L),60,List.of(),
        new ForegroundWindow(app,1,title,"window"+app),new ActivityRecord.Inference(
        "ユーザーがこれらのウィンドウを積極的に操作しているかは不明です",List.of(activities)),.8,List.of("evidence-"+minute),.1,false,"run1");
  }
  static ActivityRecord dev(int minute,String app,String service) {
    return record(minute,app,"rei.log",activity("coding",app,"","rei"),
        activity("coding","Firefox","GitHub","rei"),activity("social","Firefox",service,""),
        activity("monitoring","btop","",""));
  }
  static List<SummarySegment> segments(ActivityRecord... records) {
    return new SemanticSessionPolicy(Duration.ofMinutes(3),Duration.ofMinutes(2),ZoneOffset.UTC).aggregate(List.of(records));
  }
  @Test void developmentToolsAndSecondaryChangesMerge() {
    var result=segments(dev(0,"Terminal","X"),dev(1,"GVIM","YouTube"),dev(2,"Terminal","X"));
    assertEquals(1,result.size());assertEquals("development",result.getFirst().roles().primary().type());
    assertEquals(3,result.getFirst().evidence().size());assertEquals(180,result.getFirst().observedSeconds());
  }
  @Test void sustainedForegroundMediaSplits() {
    var youtube=activity("media","Firefox","YouTube","");
    assertEquals(2,segments(dev(0,"Terminal","X"),record(1,"Firefox","YouTube",youtube),record(2,"Firefox","YouTube",youtube),record(3,"Firefox","YouTube",youtube)).size());
  }
  @Test void monitorIsBackgroundUnlessForeground() {
    var roles=new ActivityRolePolicy().classify(dev(0,"Terminal","X"));
    assertEquals("terminal",roles.primary().application());
    assertEquals("btop",roles.background().getFirst().application());
    var monitor=activity("monitoring","btop","","");
    assertEquals(monitor,new ActivityRolePolicy().classify(record(1,"btop","system monitor",monitor)).primary());
  }
  @Test void ambiguousBrowserDoesNotInventPrimary() {
    var roles=new ActivityRolePolicy().classify(record(0,"Firefox","New tab",activity("social","Firefox","X",""),activity("media","Firefox","YouTube","")));
    assertNull(roles.primary());assertEquals(2,roles.secondary().size());
  }
  @Test void shortMissingObservationMergesWithoutCountingGapAsObserved() {
    var result=segments(dev(0,"Terminal","X"),dev(4,"GVIM","X"));
    assertEquals(1,result.size());assertEquals(120,result.getFirst().observedSeconds());assertEquals(180,result.getFirst().unobservedSeconds());
  }
  @Test void longGapAndDifferentProjectsSplit() {
    assertEquals(2,segments(dev(0,"Terminal","X"),dev(5,"GVIM","X")).size());
    assertEquals(2,segments(dev(0,"Terminal","X"),record(1,"GVIM","other",activity("coding","GVIM","","other"))).size());
  }
  @Test void shortDetourReturnsToDevelopmentWithoutLosingEvidence() {
    var result=segments(dev(0,"Terminal","X"),record(1,"Firefox","YouTube",activity("media","Firefox","YouTube","")),dev(2,"GVIM","X"));
    assertEquals(1,result.size());assertEquals(3,result.getFirst().evidence().size());
    assertTrue(result.getFirst().roles().secondary().stream().anyMatch(a->a.service().equals("youtube")));
  }
  @Test void twentyFineSessionsBecomeOneSummaryBlock() {
    var records=java.util.stream.IntStream.range(0,20).mapToObj(i->dev(i,i%2==0?"Terminal":"GVIM",i%3==0?"X":"YouTube")).toList();
    assertEquals(20,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC).aggregate(records).size());
    var result=new SemanticSessionPolicy(Duration.ofMinutes(3),Duration.ofMinutes(2),ZoneOffset.UTC).aggregate(records);
    assertEquals(1,result.size());assertEquals(20,result.getFirst().evidence().size());
    var text=new ActivitySummaryFormatter(ZoneOffset.UTC).format(result);
    assertEquals(1,text.split("実際の操作・集中",-1).length-1);
    assertFalse(text.contains("積極的に操作"));assertTrue(text.contains("rei"));assertTrue(text.contains("開発"));
    assertFalse(text.contains("バグ修正"));assertTrue(text.length()<400);
  }
  @Test void primaryWithinMergedContextUsesObservedDuration() {
    var social=activity("social","Firefox","X","");var media=activity("media","Firefox","YouTube","");
    var policy=new SemanticSessionPolicy(Duration.ofMinutes(3),Duration.ofMinutes(2),ZoneOffset.UTC);
    var s=new SummaryGroupingPolicy(policy).aggregate(segments(record(0,"Firefox","X",social),record(1,"Firefox","YouTube",media),record(2,"Firefox","YouTube",media))).getFirst();
    assertEquals("media",s.roles().primary().type());
    assertTrue(s.roles().secondary().contains(ActivityVocabulary.canonical(social)));
  }
  @Test void sustainedChangeRemainsSeparateEvenAfterReturn() {
    var video=activity("media","Firefox","YouTube","");
    assertEquals(3,segments(dev(0,"Terminal","X"),record(1,"Firefox","YouTube",video),record(2,"Firefox","YouTube",video),record(3,"Firefox","YouTube",video),dev(4,"GVIM","X")).size());
  }
  @Test void unknownProjectCannotBridgeConflictingProjects() {
    var blank=record(1,"Terminal","",activity("coding","Terminal","",""));
    var other=record(2,"Terminal","another",activity("coding","Terminal","","another"));
    assertEquals(2,segments(dev(0,"Terminal","X"),blank,other).size());
  }
  @Test void pauseContinuityBoundaryIsNeverSmoothed() {
    var a=dev(0,"Terminal","X");var b=dev(1,"Terminal","X");
    b=new ActivityRecord(b.id(),b.capturedAt(),60,b.observations(),b.foreground(),b.inference(),b.confidence(),b.screenshotReferences(),b.changeAmount(),false,"resumed");
    assertEquals(2,segments(a,b).size());
  }
  @Test void midnightAndOverlappingEstimatesDoNotInflateTime() {
    var a=dev(0,"Terminal","X");var b=dev(1,"GVIM","X");
    var midnight=Instant.parse("2026-09-23T23:59:30Z");
    a=new ActivityRecord(a.id(),midnight,120,a.observations(),a.foreground(),a.inference(),.8,List.of(),.1,false,"run");
    b=new ActivityRecord(b.id(),midnight.plusSeconds(30),60,b.observations(),b.foreground(),b.inference(),.8,List.of(),.1,false,"run");
    var result=segments(a,b);assertEquals(2,result.size());assertEquals(30,result.getFirst().observedSeconds());
    var overlapping=new ActivityRecord(a.id(),b.capturedAt().minusSeconds(10),120,a.observations(),a.foreground(),a.inference(),.8,List.of(),.1,false,"run");
    assertEquals(70,segments(overlapping,b).stream().mapToLong(SummarySegment::observedSeconds).sum());
  }
  @Test void confidenceStaysUncertainAndUnknownIsNotIdle() {
    var known=new ActivityRolePolicy().classify(dev(0,"Terminal","X"));assertTrue(known.confidence()>0 && known.confidence()<.8);
    var unknown=record(0,"unknown","",activity("idle","Firefox","",""));
    var summary=new ActivitySummaryFormatter(ZoneOffset.UTC).format(segments(unknown));
    assertTrue(summary.contains("主活動は判定できません"));assertFalse(summary.contains("離席"));
  }
  @Test void suppliedMorningExampleCompressesFiveSessionsToOneBlock() throws Exception {
    int[] starts={0,2,13,31,33};int[] durations={1,11,18,1,5};
    var records=new ArrayList<ActivityRecord>();
    for(int i=0;i<starts.length;i++) {
      var r=dev(starts[i],i%2==0?"Terminal":"GVIM",i<3?"X":"YouTube Music");
      records.add(new ActivityRecord(r.id(),r.capturedAt(),durations[i]*60L,r.observations(),r.foreground(),r.inference(),r.confidence(),r.screenshotReferences(),r.changeAmount(),false,r.continuityId()));
    }
    var fine=new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC).aggregate(records);
    var result=new SemanticSessionPolicy(Duration.ofMinutes(3),Duration.ofMinutes(2),ZoneOffset.UTC).aggregate(records);
    assertEquals(5,fine.size());assertEquals(1,result.size());assertEquals(2160,result.getFirst().observedSeconds());
    assertEquals(120,result.getFirst().unobservedSeconds());
    var text=new ActivitySummaryFormatter(ZoneOffset.UTC).format(result);
    assertTrue(text.contains("09:08–09:46"));assertTrue(text.contains("rei関連の開発・確認作業"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/activity-refinement-example.txt"),"Synthetic fixture: 5 fine sessions -> 1 summary segment\n"+text);
  }
  @Test void thresholdsAreConfigurableAndValidated() {
    var p=new ActivityProperties();assertNull(p.getSummaryGapSeconds());assertEquals(120,p.effectiveNormalGapSeconds());assertEquals(300,p.effectiveMaximumGapSeconds());assertEquals(120,p.getSummaryBriefSwitchSeconds());
    p.setSummaryGapSeconds(-1);assertThrows(IllegalArgumentException.class,p::validate);
    p.setSummaryGapSeconds(0);p.setSummaryBriefSwitchSeconds(-1);assertThrows(IllegalArgumentException.class,p::validate);
    assertEquals(2,new SemanticSessionPolicy(Duration.ZERO,Duration.ZERO,ZoneOffset.UTC).aggregate(List.of(dev(0,"Terminal","X"),dev(2,"GVIM","X"))).size());
  }
}
