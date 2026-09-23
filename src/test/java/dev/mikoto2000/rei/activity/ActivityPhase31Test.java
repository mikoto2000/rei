package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.activity.ActivitySemanticTest.*;

class ActivityPhase31Test {
  static List<SummarySegment> summarize(ActivityRecord... records) {
    var policy=new SemanticSessionPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300),Duration.ofSeconds(120),.5,ZoneOffset.UTC);
    return new SummaryGroupingPolicy(policy).aggregate(policy.aggregate(List.of(records)));
  }
  static ActivityRecord web(int minute,String category,String service) {
    return record(minute,"Firefox",service,activity(category,"Firefox",service,""));
  }
  @Test void normalGapMerges() {assertEquals(1,summarize(dev(0,"Terminal","X"),dev(2,"GVIM","X")).size());}
  @Test void extendedGapNeedsStrongAgreementAndLowersConfidence() {
    var joined=summarize(dev(0,"Terminal","X"),dev(4,"GVIM","X"));
    assertEquals(1,joined.size());assertEquals(120,joined.getFirst().observedSeconds());assertEquals(180,joined.getFirst().unobservedSeconds());
    assertTrue(joined.getFirst().roles().confidence()<summarize(dev(0,"Terminal","X")).getFirst().roles().confidence());
    assertEquals(2,summarize(web(0,"social","X"),web(4,"shopping","Amazon")).size());
  }
  @Test void longGapNeverMergesAtEitherLayer() {
    assertEquals(2,summarize(web(0,"social","X"),web(10,"social","X")).size());
  }
  @Test void cumulativeMissingTimeCannotGrowWithoutBound() {
    var result=summarize(dev(0,"Terminal","X"),dev(4,"GVIM","X"),dev(8,"Terminal","X"),dev(12,"GVIM","X"));
    assertTrue(result.size()>1);assertTrue(result.stream().allMatch(s->s.unobservedSeconds()<=300));
  }
  @Test void foregroundProcessOutranksOtherVisibleServicesEvenWhenTitleMentionsThem() {
    var r=record(0,"WindowsTerminal.exe","X YouTube",activity("coding","Local terminal","local","Rei"),activity("social","Firefox","X",""),activity("media","Firefox","YouTube",""));
    var roles=new ActivityRolePolicy().classify(r);
    assertEquals("development",roles.primary().type());assertEquals("rei",roles.primary().projectCandidate());
  }
  @Test void foregroundBrowserTitleSelectsX() {
    var r=record(0,"firefox","X (Twitter) - Firefox",activity("coding","Terminal","GitHub","rei"),activity("social","Firefox","Twitter",""),activity("media","Firefox","YouTube",""));
    assertEquals("social",new ActivityRolePolicy().classify(r).primary().type());
  }
  @Test void weakCategoriesBecomeUnknownAndRemainSeparateFromTools() {
    for(var category:List.of("Terminal","Shell","Web","local")) {
      var roles=new ActivityRolePolicy().classify(record(0,"Terminal","",activity(category,"Terminal","local","")));
      assertNull(roles.primary());assertEquals("unknown",roles.category());
      assertTrue(roles.secondary().stream().allMatch(a->a.type().equals("unknown")));
    }
  }
  @Test void lowConfidenceFallsBackToUnknown() {
    var r=dev(0,"Terminal","X");
    r=new ActivityRecord(r.id(),r.capturedAt(),60,r.observations(),r.foreground(),r.inference(),.2,r.screenshotReferences(),.5,false,r.continuityId());
    assertNull(new ActivityRolePolicy().classify(r).primary());
  }
  @Test void browserThemeCombinesShoppingAndSocial() {
    var result=summarize(web(0,"social","X"),web(1,"shopping","Amazon"),web(2,"social","Twitter"));
    assertEquals(1,result.size());assertEquals("web-browsing",result.getFirst().theme());
    assertEquals(3,result.getFirst().evidence().size());
  }
  @Test void workThemeRequiresCompatibleProject() {
    var research=record(1,"Firefox","rei GitHub",activity("research","Firefox","GitHub","Rei"));
    assertEquals(1,summarize(dev(0,"Terminal","X"),research,dev(2,"GVIM","X")).size());
    var other=record(1,"Firefox","other GitHub",activity("research","Firefox","GitHub","other"));
    assertEquals(3,summarize(dev(0,"Terminal","X"),other,dev(2,"GVIM","X")).size());
  }
  @Test void labelsAreNormalizedAndGenericServiceNamesAreHidden() {
    var r=record(0,"Terminal","rei",activity("Development","PowerShell","local","Rei"),activity("social","Firefox","X (Twitter)",""));
    String text=new ActivitySummaryFormatter(ZoneOffset.UTC).format(summarize(r));
    assertTrue(text.contains("rei関連"));assertTrue(text.contains("X"));assertFalse(text.contains("Twitter"));assertFalse(text.contains("local"));
  }
  @Test void unknownSummaryStillShowsBoundedVisibleEvidence() {
    var r=record(0,"unmatched","",activity("local","Local terminal","Shell",""),activity("social","Firefox","Twitter",""),activity("media","Firefox","YouTube",""));
    var text=new ActivitySummaryFormatter(ZoneOffset.UTC).format(summarize(r));
    assertTrue(text.contains("主活動は判定できません"));assertTrue(text.contains("X"));assertTrue(text.contains("ターミナル"));assertFalse(text.contains("画面上の活動"));
  }
  @Test void missingRatioControlsHumanReadableNotes() {
    var r=dev(0,"Terminal","X");var roles=new ActivityRolePolicy().classify(r);var formatter=new ActivitySummaryFormatter(ZoneOffset.UTC);
    for(int missing:List.of(9,10,30,31)) {
      var s=new SummarySegment(START,START.plusSeconds(100),100-missing,roles,List.of(r),List.of());
      var text=formatter.format(List.of(s));
      assertFalse(text.contains("秒"));
      if(missing<10) assertFalse(text.contains("未観測"));
      else if(missing<=30) assertTrue(text.contains("一部未観測時間あり"));
      else assertTrue(text.contains("観測できた時間帯のみ"));
    }
  }
  @Test void suppliedBrowsingExampleSplitsAtElevenMinuteGap() {
    var records=new ArrayList<ActivityRecord>();int[] offsets={0,2,19,35};int[] durations={1,16,5,5};
    for(int i=0;i<4;i++) {var r=web(offsets[i],i==0?"shopping":"social",i==0?"Amazon":"X");records.add(new ActivityRecord(r.id(),r.capturedAt(),durations[i]*60L,r.observations(),r.foreground(),r.inference(),r.confidence(),r.screenshotReferences(),.1,false,r.continuityId()));}
    var result=summarize(records.toArray(ActivityRecord[]::new));
    assertEquals(2,result.size());assertEquals(3,result.getFirst().evidence().size());
  }
  @Test void halfDayFixtureCompressesTwentyFourFineSessionsToSixThemes() throws Exception {
    var records=new ArrayList<ActivityRecord>();
    for(int block=0;block<6;block++) {
      records.add(web(block*60,"social","X"));records.add(web(block*60+2,"shopping","Amazon"));
      records.add(web(block*60+4,"media","YouTube"));records.add(web(block*60+6,"social","Twitter"));
    }
    var fine=new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC).aggregate(records);
    var result=summarize(records.toArray(ActivityRecord[]::new));
    assertEquals(24,fine.size());assertEquals(6,result.size());assertEquals(24,result.stream().mapToInt(s->s.evidence().size()).sum());
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/activity31-example.txt"),"Synthetic half-day: 24 fine sessions -> 6 summary themes\n"+new ActivitySummaryFormatter(ZoneOffset.UTC).format(result));
  }
  @Test void exactGapBoundariesAndLegacySettingsAreHonored() {
    var gaps=new SessionGapPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300));
    assertTrue(gaps.allows(Duration.ofSeconds(120),120,false));assertFalse(gaps.allows(Duration.ofSeconds(121),121,false));
    assertTrue(gaps.allows(Duration.ofSeconds(300),300,true));assertFalse(gaps.allows(Duration.ofSeconds(301),301,true));
    assertFalse(gaps.allows(Duration.ofSeconds(60),301,true));
    var p=new ActivityProperties();p.setSummaryGapSeconds(90);assertEquals(90,p.effectiveNormalGapSeconds());assertEquals(90,p.effectiveMaximumGapSeconds());
    p.setPrimaryConfidenceThreshold(Double.NaN);assertThrows(IllegalArgumentException.class,p::validate);
    p.setPrimaryConfidenceThreshold(.5);p.setSummaryNormalMergeGapSeconds(301);assertThrows(IllegalArgumentException.class,p::validate);
  }
  @Test void unknownWithOnlyBackgroundEvidenceStillShowsWhatWasVisible() {
    var r=record(0,"unmatched","",activity("monitoring","btop","",""));
    var text=new ActivitySummaryFormatter(ZoneOffset.UTC).format(summarize(r));
    assertTrue(text.contains("主活動は判定できません"));assertTrue(text.contains("btop"));
  }
}
