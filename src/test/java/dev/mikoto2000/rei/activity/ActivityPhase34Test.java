package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.activity.ActivitySemanticTest.*;
import static dev.mikoto2000.rei.activity.ActivityTrendTest.*;

class ActivityPhase34Test {
  private static ActivityRecord sample(int minute,String category,String project) {
    String app=Set.of("social","media","shopping").contains(category)?"Firefox":"Terminal";
    String service=app.equals("Firefox")?"X":"";
    return record(minute,app,project.isBlank()?service:project,
        new ActivityRecord.Activity("m1",category,app,service,project,project),activity("other","Firefox","GitHub",""));
  }
  private static List<ActivityRecord> block(int start,int minutes,String category,String project) {
    return java.util.stream.IntStream.range(start,start+minutes).mapToObj(i->sample(i,category,project)).toList();
  }
  private static List<TrendSummarySegment> summarize(List<ActivityRecord> records) {
    return trends(records.toArray(ActivityRecord[]::new));
  }
  private static List<ActivityRecord> concat(List<ActivityRecord> a,List<ActivityRecord> b) {
    var result=new ArrayList<>(a);result.addAll(b);return result;
  }
  private static String text(List<TrendSummarySegment> segments) {return new TrendSummaryFormatter(ZoneOffset.UTC).format(segments);}

  @Test void terminalAliasesIncludeCaseWhitespaceAndPunctuation() {
    for(String value:List.of("WindowsTerminal","Windows Terminal","Windows-Terminal","cmd","PowerShell","Local terminal","Terminal"," terminal "))
      assertEquals("ターミナル",ActivityDisplayLabels.label(value),value);
  }
  @Test void editorAndLogAliasesAreReadable() {
    for(String value:List.of("Text Editor","text editor","TEXT_EDITOR","Local editor","editor")) assertEquals("エディタ",ActivityDisplayLabels.label(value));
    assertEquals("ローカルログ",ActivityDisplayLabels.label("Local log file"));
    assertEquals("ローカルログ",ActivityDisplayLabels.label("log file"));
  }
  @Test void twitterAliasesAreOneService() {
    for(String value:List.of("Twitter","X (Twitter)","X (旧Twitter)","X（旧Twitter）","x browsing")) assertEquals("X",ActivityDisplayLabels.label(value));
  }
  @Test void duplicateLabelsDoNotConsumeDisplaySlotsAndRawValuesSurvive() {
    var r=record(0,"Terminal","rei",activity("development","Terminal","","rei"),
        activity("other","WindowsTerminal","",""),activity("other","Text Editor","",""),
        activity("other","text editor","",""),activity("other","Local log file","",""));
    var s=trends(r).getFirst();assertEquals(Set.of("ターミナル","エディタ","ローカルログ"),new HashSet<>(s.labels()));
    assertEquals(r,s.evidence().getFirst());assertTrue(text(List.of(s)).contains("ローカルログ"));
  }
  @Test void serviceActivityApplicationAndTopicAreNotProjects() {
    for(String candidate:List.of("x browsing","X","GitHub","YouTube","ChatGPT","Firefox","WindowsTerminal","development","機能設計の提案")) {
      var s=summarize(block(0,3,"development",candidate));
      assertTrue(s.getFirst().projects().isEmpty(),candidate);
      assertFalse(text(s).contains(candidate+"関連"),candidate);
      assertEquals(candidate,s.getFirst().evidence().getFirst().inference().activities().getFirst().projectCandidate());
    }
  }
  @Test void validProjectIdentifiersArePreserved() {
    for(String project:List.of("rei","yagisan-reports","another_project")) {
      var s=summarize(block(0,5,"development",project));
      assertEquals(List.of(project),s.getFirst().projects());assertTrue(text(s).contains(project+"関連"));
    }
  }
  @Test void stableFiftyFiveMinuteThemeIsNotCutAtFortyFiveMinutes() {
    var records=block(0,55,"development","rei");var result=summarize(records);
    assertEquals(1,result.size());assertEquals(3300,result.getFirst().observedSeconds());assertEquals(records,result.getFirst().evidence());
  }
  @Test void sustainedThemeSwitchSplitsFiftyMinuteWindow() {
    var records=concat(block(0,30,"social",""),block(30,20,"development","rei"));
    var result=summarize(records);assertEquals(2,result.size());
    assertEquals(START.plusSeconds(30*60),result.getFirst().endedAt());
    assertEquals(START.plusSeconds(30*60),result.getLast().startedAt());
    assertEquals(records,result.stream().flatMap(s->s.evidence().stream()).toList());
  }
  @Test void projectSwitchSplitsEvenShortWindow() {
    var result=summarize(concat(block(0,10,"development","rei"),block(10,10,"documentation","yagisan-reports")));
    assertEquals(2,result.size());assertEquals(List.of("rei"),result.getFirst().projects());assertEquals(List.of("yagisan-reports"),result.getLast().projects());
  }
  @Test void sustainedWorkToLeisureSplitsButBriefDetourDoesNot() {
    assertEquals(2,summarize(concat(block(0,10,"development",""),block(10,10,"media",""))).size());
    var detour=concat(concat(block(0,20,"development","rei"),block(20,2,"social","")),block(22,30,"development","rei"));
    assertEquals(1,summarize(detour).size());
  }
  @Test void sameLeisureFamilyCanStayTogether() {
    assertEquals(1,summarize(concat(block(0,15,"social",""),block(15,15,"media",""))).size());
  }
  @Test void softLimitTriggersCategoryBoundarySearchWithoutFixedTimeCut() {
    var records=concat(block(0,30,"development","rei"),block(30,25,"documentation","rei"));
    var result=summarize(records);assertEquals(2,result.size());
    assertEquals(START.plusSeconds(1800),result.getLast().startedAt());
    assertEquals(1,new TrendSummaryPolicy(ZoneOffset.UTC,.5,Duration.ofMinutes(60),Duration.ofMinutes(20),Duration.ofMinutes(60))
        .aggregate(fine(records.toArray(ActivityRecord[]::new))).size());
  }
  @Test void longGapCanSplitSoftLimitWithoutCountingMissingTimeAsObserved() {
    var records=concat(block(0,20,"development","rei"),block(30,20,"development","rei"));
    var result=summarize(records);assertEquals(2,result.size());
    assertEquals(2400,result.stream().mapToLong(TrendSummarySegment::observedSeconds).sum());
    assertEquals(records,result.stream().flatMap(s->s.evidence().stream()).toList());
  }
  @Test void unknownBetweenStableThemesDoesNotHideSwitchOrLoseEvidence() {
    var records=concat(concat(block(0,10,"development","rei"),List.of(unknown(10))),block(11,10,"social",""));
    var result=summarize(records);assertEquals(2,result.size());
    assertEquals(records,result.stream().flatMap(s->s.evidence().stream()).toList());
    assertEquals(60,result.stream().mapToLong(TrendSummarySegment::unknownSeconds).sum());
  }
  @Test void evidenceBasedExamplesAreExportable() throws Exception {
    var continuous=summarize(block(0,38,"development","rei"));
    var mixed=trends(sample(0,"development","x browsing"),sample(2,"social",""),sample(4,"development","x browsing"));
    var split=summarize(concat(block(0,30,"social",""),block(30,20,"development","rei")));
    String output="Synthetic examples (not a replay of the user's database)\n"+text(continuous)+"\n"+text(mixed)+"\n"+text(split);
    assertFalse(output.contains("x browsing"));assertTrue(output.contains("混在"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/activity34-example.txt"),output);
  }
  @Test void splitInspectsEvidenceInsideExistingSummaryProjection() {
    var records=concat(block(0,25,"social",""),block(25,25,"development","rei"));
    var roles=new ActivityRolePolicy(.5).classify(records.getFirst());
    var source=new SummarySegment(START,START.plusSeconds(3000),3000,roles,records,List.of("fine-source"));
    var result=new TrendSummaryPolicy(ZoneOffset.UTC,.5).aggregate(List.of(source));
    assertEquals(2,result.size());assertEquals(START.plusSeconds(1500),result.getLast().startedAt());
    assertEquals(records,result.stream().flatMap(s->s.evidence().stream()).toList());
    assertTrue(result.stream().allMatch(s->s.sourceSegments().equals(List.of(source))));
    assertEquals(3000,result.stream().mapToLong(TrendSummarySegment::observedSeconds).sum());
  }
  @Test void continuousStrongEvidenceUsesNaturalActivityWording() {
    String result=text(summarize(block(0,38,"development","rei")));
    assertTrue(result.contains("rei関連の開発・確認が中心"));assertFalse(result.contains("観測できた範囲では"));
    assertFalse(result.contains("に関する画面"));assertFalse(result.contains("バグ修正"));assertFalse(result.contains("集中して"));
  }
  @Test void sparseObservationsKeepQualification() {
    String result=text(trends(sample(0,"development","rei"),sample(15,"development","rei")));
    assertTrue(result.contains("観測できた範囲では"));assertTrue(result.contains("断続的"));
  }
  @Test void weakPrimaryConfidenceDoesNotGetStrongContinuousWording() {
    var r=sample(0,"development","rei");
    r=new ActivityRecord(r.id(),r.capturedAt(),60,r.observations(),r.foreground(),r.inference(),.6,r.screenshotReferences(),.1,false,r.continuityId());
    String result=text(trends(r));assertTrue(result.contains("観測できた範囲では"));assertFalse(result.contains("が中心"));
  }
  @Test void mixedAndUnknownWordingStayCautious() {
    String mixed=text(trends(sample(0,"development","rei"),sample(2,"social",""),sample(4,"development","rei")));
    assertTrue(mixed.contains("混在"));assertTrue(mixed.contains("観測できた範囲では"));
    String unknown=text(trends(unknown(0)));assertTrue(unknown.contains("主活動は判定できません"));assertFalse(unknown.contains("が中心"));
  }
}
