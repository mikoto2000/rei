package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.activity.ActivitySemanticTest.*;

class ActivityTrendTest {
  static List<SummarySegment> fine(ActivityRecord... records) {
    var p=new SemanticSessionPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300),Duration.ofSeconds(120),.5,ZoneOffset.UTC);
    return new SummaryGroupingPolicy(p).aggregate(p.aggregate(List.of(records)));
  }
  static List<TrendSummarySegment> trends(ActivityRecord... records) {return new TrendSummaryPolicy(ZoneOffset.UTC,.5).aggregate(fine(records));}
  static ActivityRecord unknown(int minute) {return record(minute,"unmatched","",activity("social","Firefox","X",""),activity("media","Firefox","YouTube",""),activity("coding","Terminal","","rei"));}
  @Test void unknownsAreAbsorbedIntoNearbyTrendWithoutRelabelingEvidence() {
    var records=List.of(unknown(0),ActivityPhase31Test.web(11,"social","X"),unknown(13),ActivityPhase31Test.web(28,"social","X"),unknown(30));
    var result=trends(records.toArray(ActivityRecord[]::new));
    assertEquals(1,result.size());var s=result.getFirst();
    assertEquals(TrendSummarySegment.Continuity.INTERMITTENT,s.continuity());
    assertEquals(180,s.unknownSeconds());assertEquals(120,s.knownSeconds());assertEquals(records,s.evidence());
    assertTrue(new TrendSummaryFormatter(ZoneOffset.UTC).format(result).contains("主活動を判定できない"));
  }
  @Test void longObservationGapIsNotContinuousActivity() {
    var result=trends(ActivityPhase31Test.web(0,"social","X"),ActivityPhase31Test.web(16,"social","X"));
    assertEquals(1,result.size());assertEquals(TrendSummarySegment.Continuity.INTERMITTENT,result.getFirst().continuity());
    assertEquals(120,result.getFirst().observedSeconds());assertEquals(900,result.getFirst().unobservedSeconds());
    String text=new TrendSummaryFormatter(ZoneOffset.UTC).format(result);
    assertTrue(text.contains("観測できた範囲"));assertFalse(text.contains("ずっと"));assertFalse(text.contains("未観測の割合が高い"));
  }
  @Test void continuousDevelopmentHasExplicitSemantics() {
    var s=trends(dev(0,"Terminal","X"),dev(1,"GVIM","X"),dev(2,"Terminal","X")).getFirst();
    assertEquals(TrendSummarySegment.Continuity.CONTINUOUS,s.continuity());assertEquals(0,s.unobservedSeconds());assertEquals(List.of("rei"),s.projects());
  }
  @Test void sameProjectWorkCategoriesGroupButDifferentProjectsDoNot() {
    var research=record(10,"Firefox","GitHub",activity("research","Firefox","GitHub","rei"));
    var docs=record(15,"GVIM","rei",activity("documentation","GVIM","","rei"));
    assertEquals(1,trends(dev(0,"Terminal","X"),research,docs).size());
    var other=record(10,"GVIM","reports",activity("coding","GVIM","","yagisan-reports"));
    assertEquals(2,trends(dev(0,"Terminal","X"),other).size());
  }
  @Test void otherIsUnknownForPresentationOnly() {
    var r=record(0,"Terminal","",activity("other","Terminal","",""));
    var s=trends(r).getFirst();assertEquals(60,s.unknownSeconds());assertEquals("other",s.evidence().getFirst().inference().activities().getFirst().type());
    var text=new TrendSummaryFormatter(ZoneOffset.UTC).format(List.of(s));assertTrue(text.contains("主活動は判定できません"));assertFalse(text.contains("その他の活動が中心"));
  }
  @Test void displayLabelsAreNormalizedWithoutGuessingProperNouns() {
    for(var pair:Map.of("browser","ブラウザ","cmd","ターミナル","python","Python関連","chatgpt","ChatGPT","shopping","ショッピング","gradle","ビルド","openai","AIツール").entrySet())
      assertEquals(pair.getValue(),ActivityDisplayLabels.label(pair.getKey()));
    assertEquals("spomin dashboard",ActivityDisplayLabels.label("spomin dashboard"));
  }
  @Test void labelsAreBoundedAndFrequentForegroundServiceWins() {
    var r=record(0,"Firefox","X",activity("other","browser","obscure1",""),activity("other","browser","obscure2",""),activity("social","Firefox","Twitter",""),activity("media","browser","YouTube",""),activity("coding","Terminal","","rei"));
    var s=trends(r,ActivityPhase31Test.web(2,"social","X"),ActivityPhase31Test.web(4,"social","X")).getFirst();
    assertTrue(s.labels().size()<=4);assertEquals("X",s.labels().getFirst());
  }
  @Test void noInventedActivityAndVisibleOnlyHasCautiousWording() {
    var text=new TrendSummaryFormatter(ZoneOffset.UTC).format(trends(unknown(0),unknown(10)));
    assertFalse(text.contains("バグ修正"));assertFalse(text.contains("操作した"));assertTrue(text.contains("表示"));
  }
  @Test void gapAndTimeWindowStillBoundTrendExtent() {
    assertEquals(2,trends(unknown(0),unknown(22)).size());
    assertEquals(2,trends(unknown(0),unknown(15),unknown(30),unknown(45),unknown(60)).size());
  }
  @Test void mixedWorkAndBrowsingAreNotContinuous() {
    var s=trends(dev(0,"Terminal","X"),ActivityPhase31Test.web(3,"social","X"),dev(5,"GVIM","X")).getFirst();
    assertEquals(TrendSummarySegment.Continuity.MIXED,s.continuity());
  }
  @Test void providedMorningFixtureCompressesThirtyFiveSessionsIntoFiveToTenTrends() throws Exception {
    int[][] times={{0,4,6,10,18},{39,50,54,67,78},{88,95,110,125,140},{148,153,162,177,186},{197,201,205,210,214},{224,228,235,239,243},{254,258,263,267,270}};
    var base=Instant.parse("2026-09-23T07:24:00Z");var records=new ArrayList<ActivityRecord>();
    for(int blockIndex=0;blockIndex<times.length;blockIndex++) for(int i=0;i<times[blockIndex].length;i++) {
      int minute=times[blockIndex][i];
      var r=i%2==0?unknown(minute):ActivityPhase31Test.web(minute,"social","X");
      if(blockIndex==2) r=i==2?unknown(minute):dev(minute,i%2==0?"Terminal":"GVIM","X");
      if(blockIndex==3 && i==0) r=ActivityPhase31Test.web(minute,"shopping","Amazon");
      if(blockIndex==4 && i==1) r=dev(minute,"Terminal","X");
      if(blockIndex==5 && i==1) r=record(minute,"Firefox","Slack",activity("communication","Firefox","Slack",""));
      if(blockIndex==6 && i>0) r=record(minute,"GVIM","yagisan-reports",activity("documentation","GVIM","","yagisan-reports"),activity("research","Firefox","python",""));
      records.add(new ActivityRecord(r.id(),base.plusSeconds(minute*60L),60,r.observations(),r.foreground(),r.inference(),r.confidence(),r.screenshotReferences(),.1,false,r.continuityId()));
    }
    assertEquals(35,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC).aggregate(records).size());
    var result=trends(records.toArray(ActivityRecord[]::new));assertTrue(result.size()>=5 && result.size()<=10,"count="+result.size());
    assertEquals(35,result.stream().mapToInt(s->s.evidence().size()).sum());
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/activity32-example.txt"),"Synthetic morning: 35 fine sessions -> "+result.size()+" trends\n"+new TrendSummaryFormatter(ZoneOffset.UTC).format(result));
  }
  @Test void exactEightOClockExampleBecomesOneIntermittentTrend() {
    int[] starts={0,11,13,15,26,28,35,37,39};int[] lengths={1,1,1,9,1,5,1,1,1};
    var records=new ArrayList<ActivityRecord>();var base=Instant.parse("2026-09-23T08:03:00Z");
    for(int i=0;i<starts.length;i++) {
      var r=Set.of(0,1,2,4,7).contains(i)?unknown(starts[i]):ActivityPhase31Test.web(starts[i],"social","X");
      records.add(new ActivityRecord(r.id(),base.plusSeconds(starts[i]*60L),lengths[i]*60L,r.observations(),r.foreground(),r.inference(),r.confidence(),r.screenshotReferences(),.1,false,r.continuityId()));
    }
    var result=trends(records.toArray(ActivityRecord[]::new));assertEquals(1,result.size());
    assertEquals(base,result.getFirst().startedAt());assertEquals(base.plusSeconds(40*60),result.getFirst().endedAt());
    assertEquals(TrendSummarySegment.Continuity.INTERMITTENT,result.getFirst().continuity());
    assertEquals(1260,result.getFirst().observedSeconds());assertEquals(1140,result.getFirst().unobservedSeconds());
  }
  @Test void continuityChangeAndMidnightCannotBeCalledContinuous() {
    var a=dev(0,"Terminal","X");var b=dev(1,"GVIM","X");
    b=new ActivityRecord(b.id(),b.capturedAt(),60,b.observations(),b.foreground(),b.inference(),.8,List.of(),.1,false,"resumed");
    assertEquals(TrendSummarySegment.Continuity.INTERMITTENT,trends(a,b).getFirst().continuity());
    var end=Instant.parse("2026-09-23T23:59:30Z");
    a=new ActivityRecord(a.id(),end,60,a.observations(),a.foreground(),a.inference(),.8,List.of(),.1,false,"same");
    b=new ActivityRecord(b.id(),end.plusSeconds(30),60,b.observations(),b.foreground(),b.inference(),.8,List.of(),.1,false,"same");
    assertEquals(2,trends(a,b).size());
  }
}
