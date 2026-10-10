package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopTemporalFusionTest {
  static ActivityRecord supplemented(String id,Instant at) {
    var r=TemporalActivityInferenceTest.record(id,at,"コード修正");
    return ActivityBackgroundMerge.merge(r,new ActivityExtractor.Result(new ActivityRecord.Inference("背景候補",List.of(new ActivityRecord.Activity("monitor-2","research","Chrome","","Spring Security 仕様","rei"))),.9),.5);
  }
  @Test void includesAttributedDesktopInferenceButExpiresOldContext() {
    var at=TemporalActivityInferenceTest.AT;
    var r=supplemented("a",at);
    var input=TemporalActivityEvidence.build(List.of(r,supplemented("b",at.plusSeconds(15))),List.of(),at,at.plusSeconds(15));
    assertEquals("monitor-2",input.groups().getFirst().facts().desktop().getFirst().monitor());
    assertTrue(input.structuredInput(12000).contains("Spring Security"));
    assertTrue(input.ruleInference().inferredActivity().contains("補助"));
    var old=TemporalActivityEvidence.build(List.of(r),List.of(),at,at.plusSeconds(301));
    assertTrue(old.groups().getFirst().facts().desktop().isEmpty());
  }
  @Test void idleAndLongGapBreakTaskContinuity() {
    var at=TemporalActivityInferenceTest.AT;
    var a=supplemented("a",at);var b=supplemented("b",at.plusSeconds(120));
    assertEquals("UNKNOWN",TemporalActivityEvidence.build(List.of(a,b),List.of(),at,at.plusSeconds(120)).ruleInference().status());
    var idle=b.detection().evidence().withInput(new ActivityEvidence.InputReference(false,true));
    var d=b.detection();var updated=new ActivityRecord.Detection(idle,d.classificationSources(),false,d.classificationMode(),d.status(),d.sourceConfidence(),d.reason(),d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),d.visionDiagnostics());
    b=new ActivityRecord(b.id(),at.plusSeconds(15),15,b.observations(),b.foreground(),b.inference(),b.confidence(),List.of(),0,false,b.continuityId(),updated);
    assertEquals("UNKNOWN",TemporalActivityEvidence.build(List.of(a,b),List.of(),at,at.plusSeconds(15)).ruleInference().status());
  }
  @Test void videoNeverBecomesTaskGoalOrAddsTime() {
    var at=TemporalActivityInferenceTest.AT;var r=TemporalActivityInferenceTest.record("a",at,"コード修正");
    var video=new ActivityRecord.Activity("monitor-2","media","Chrome","youtube","放置動画","fake-project");
    var merged=ActivityBackgroundMerge.merge(r,new ActivityExtractor.Result(new ActivityRecord.Inference("",List.of(video)),.9),.5);
    var input=TemporalActivityEvidence.build(List.of(merged,TemporalActivityInferenceTest.record("b",at.plusSeconds(15),"コード修正")),List.of(),at,at.plusSeconds(15));
    assertFalse(input.ruleInference().inferredActivity().contains("動画"));
    assertFalse(input.ruleInference().inferredActivity().contains("fake-project"));
    assertEquals("rei",input.ruleInference().project());
    assertEquals(30,ActivityQualityEvaluation.compareDesktop(List.of(merged,TemporalActivityInferenceTest.record("b",at.plusSeconds(15),"コード修正"))).rows().getLast().observedSeconds());
  }
  @Test void foregroundTransitionsAreSummarizedTogetherWithoutInventingAGoal() {
    var at=TemporalActivityInferenceTest.AT;
    var input=TemporalActivityEvidence.build(List.of(supplemented("a",at),TemporalActivityInferenceTest.record("b",at.plusSeconds(15),"テスト検証")),List.of(),at,at.plusSeconds(15));
    var text=input.ruleInference().inferredActivity();
    assertTrue(text.contains("コード修正"));assertTrue(text.contains("テスト検証"));assertTrue(text.contains("可能性"));
  }
  @Test void researchWithoutProjectTitleCanJoinAnExplicitDevelopmentAnchor() {
    var at=TemporalActivityInferenceTest.AT;
    var code=supplemented("code",at);
    var browser=transition("browser",at.plusSeconds(15),"Chrome","research","Spring Security 仕様","");
    var test=transition("test",at.plusSeconds(30),"Terminal","development","Maven テスト結果","");
    var input=TemporalActivityEvidence.build(List.of(code,browser,test),List.of(),at,at.plusSeconds(30));
    assertEquals("INFERRED",input.ruleInference().status());
    assertEquals("rei",input.ruleInference().project());
    assertTrue(input.ruleInference().inferredActivity().contains("Spring Security"));
    assertTrue(input.ruleInference().inferredActivity().contains("実装・検証"));
    assertTrue(input.ruleInference().confidence()<=.6);
    var unrelated=transition("video",at.plusSeconds(30),"Chrome","media","YouTube","");
    assertEquals("UNKNOWN",TemporalActivityEvidence.build(List.of(code,browser,unrelated),List.of(),at,at.plusSeconds(30)).ruleInference().status());
  }
  private static ActivityRecord transition(String id,Instant at,String process,String category,String content,String candidate) {
    var base=TemporalActivityInferenceTest.record(id,at,content);var fg=new ForegroundWindow(process,1,content,id);
    var d=base.detection();var e=d.evidence();
    e=new ActivityEvidence(at,fg,List.of(),e.projectName(),e.projectId(),e.events(),e.history(),e.workContext(),new ActivityEvidence.InputReference(true,true));
    d=new ActivityRecord.Detection(e,d.classificationSources(),false,d.classificationMode(),d.status(),d.sourceConfidence(),d.reason());
    return new ActivityRecord(id,at,15,List.of(),fg,new ActivityRecord.Inference("",List.of(new ActivityRecord.Activity("monitor",""+category,process,"",content,candidate))),.9,List.of(),0,false,base.continuityId(),d);
  }
  @Test void verboseTimelineShowsSupplementalInferenceWithProvenanceAndNoTimeCredit() {
    var record=supplemented("aux-id",TemporalActivityInferenceTest.AT);
    var text=new ActivityEvidenceDisplayFormatter().verbose(record);
    assertTrue(text.contains("補助画面の推定"));assertTrue(text.contains("時間加算なし"));
    assertTrue(text.contains("monitor-2"));assertTrue(text.contains("Spring Security 仕様"));
    assertTrue(text.contains("aux-id"));assertTrue(text.contains(record.capturedAt().toString()));
  }
}
