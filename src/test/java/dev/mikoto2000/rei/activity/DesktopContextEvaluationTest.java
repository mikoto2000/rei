package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopContextEvaluationTest {
  @Test void comparesSameObservationsWithoutAddingBackgroundTimeOrClaimingAccuracy() {
    var a=TemporalActivityInferenceTest.record("a",TemporalActivityInferenceTest.AT,"テスト修正");
    var b=TemporalActivityInferenceTest.record("b",TemporalActivityInferenceTest.AT.plusSeconds(15),"テスト修正");
    var report=ActivityQualityEvaluation.compareDesktop(List.of(a,b));
    assertEquals(List.of("A","B","C"),report.rows().stream().map(ActivityQualityEvaluation.DesktopRow::mode).toList());
    assertFalse(report.truthVerified());
    for(var row:report.rows())assertEquals(30,row.observedSeconds());
    assertEquals(0,report.rows().getFirst().contextCandidates());
    assertTrue(report.rows().getLast().temporalInference().contains("可能性"));
  }
  @org.junit.jupiter.api.TestFactory
  java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> replayEightFixedScenarios() throws Exception {
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var fixtures=json.readTree(getClass().getResourceAsStream("/evaluation/activity-desktop-context.json"));
    assertEquals(8,fixtures.size());
    var tests=new ArrayList<org.junit.jupiter.api.DynamicTest>();
    for(var fixture:fixtures)tests.add(org.junit.jupiter.api.DynamicTest.dynamicTest(fixture.path("id").asText(),()->{
      var at=TemporalActivityInferenceTest.AT;var records=new ArrayList<ActivityRecord>();
      for(int i=0;i<4;i++) {
        var record=TemporalActivityInferenceTest.record("r"+i,at.plusSeconds(i*15),fixture.path("content").asText());
        String process=fixture.path("process").asText();
        if(fixture.path("id").asText().equals("research-to-ide") && i>0)process="IDE";
        if(fixture.path("id").asText().equals("rapid-switch") && i%2==1)process="Chrome";
        var fg=new ForegroundWindow(process,1,fixture.path("title").asText(),"window-"+process);
        var d=record.detection();var e=d.evidence();
        e=new ActivityEvidence(e.capturedAt(),fg,e.visibleWindows(),e.projectName(),e.projectId(),e.events(),e.history(),e.workContext(),
            new ActivityEvidence.InputReference(!fixture.path("idle").asBoolean(),true));
        d=new ActivityRecord.Detection(e,d.classificationSources(),false,d.classificationMode(),d.status(),d.sourceConfidence(),d.reason());
        record=new ActivityRecord(record.id(),record.capturedAt(),15,List.of(),fg,new ActivityRecord.Inference("candidate",List.of(new ActivityRecord.Activity("monitor-1","development",process,"",fixture.path("content").asText(),"rei"))),.9,List.of(),0,false,"session",d);
        if(!fixture.path("missing").asBoolean()) {
          var candidate=new ActivityRecord.Activity(fixture.path("id").asText().equals("multi-monitor")?"monitor-2":"monitor-1",
              fixture.path("id").asText().equals("abandoned-video")?"media":"research","Chrome","",fixture.path("background").asText(),"rei");
          record=ActivityBackgroundMerge.merge(record,new ActivityExtractor.Result(new ActivityRecord.Inference("",List.of(candidate)),.9),.5);
          if(fixture.path("delaySeconds").asInt()>0) {
            d=record.detection();var vision=d.visionDiagnostics().timing(true,new VisionDiagnostics.Timing(record.id(),record.capturedAt(),record.capturedAt(),at.plusSeconds(360)));
            d=new ActivityRecord.Detection(e,d.classificationSources(),true,d.classificationMode(),d.status(),d.sourceConfidence(),d.reason(),d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),vision);
            record=new ActivityRecord(record.id(),record.capturedAt(),15,List.of(),fg,record.inference(),record.confidence(),List.of(),0,false,"session",d);
          }
        }
        records.add(record);
      }
      var report=ActivityQualityEvaluation.compareDesktop(records);
      assertTrue(report.rows().stream().allMatch(row->row.observedSeconds()==60 && row.primaryChanges()==0));
      assertFalse(report.truthVerified());
      var temporal=report.rows().getLast().temporalInference();
      assertFalse(temporal.contains("動画"));
      if(fixture.path("idle").asBoolean())assertTrue(temporal.contains("推定不能"));
      if(fixture.path("delaySeconds").asInt()>0)assertFalse(temporal.contains("補助"));
    }));
    return tests.stream();
  }
  @Test void syntheticSteadyWorkKeepsOsCoverageAndBoundsDesktopCalls() throws Exception {
    var f=new DesktopContextQueueTest.Fixture();
    for(int i=0;i<=20;i++){f.pipeline.tick();while(!f.tasks.isEmpty())f.run();f.time.advance(15);}
    verifyCoverage(f);
    assertEquals(2,f.pipeline.queueMetrics().apiCalls());
    var records=org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);org.mockito.Mockito.verify(f.store,org.mockito.Mockito.atLeastOnce()).replace(records.capture());
    assertEquals(2,records.getAllValues().stream().filter(r->r.detection().visionDiagnostics().background().state()==VisionDiagnostics.State.USED).count());
    System.out.println("Desktop synthetic comparison: observations=21 foreground_calls=2 desktop_calls=2 total_calls=4 max_parallel=1 pending_slots=2 screenshot_writes=0");
  }
  private static void verifyCoverage(DesktopContextQueueTest.Fixture f)throws Exception {
    org.mockito.Mockito.verify(f.store,org.mockito.Mockito.times(21)).append(org.mockito.ArgumentMatchers.any());
  }
  @Test void reusedHistoryIsNotCountedAsAnotherVisionCall() {
    var a=TemporalActivityInferenceTest.record("a",TemporalActivityInferenceTest.AT,"code");
    var b=TemporalActivityInferenceTest.record("b",TemporalActivityInferenceTest.AT.plusSeconds(15),"code");
    var timing=new VisionDiagnostics.Timing(a.id(),a.capturedAt(),a.capturedAt(),a.capturedAt().plusSeconds(1));
    var records=new ArrayList<ActivityRecord>();
    for(var r:List.of(a,b)) {
      var d=r.detection();var vision=VisionDiagnostics.initial().with(false,r==a?VisionDiagnostics.State.USED:VisionDiagnostics.State.NOT_ATTEMPTED,null).timing(false,timing);
      d=new ActivityRecord.Detection(d.evidence(),d.classificationSources(),r==a,d.classificationMode(),d.status(),d.sourceConfidence(),d.reason(),d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),vision);
      records.add(new ActivityRecord(r.id(),r.capturedAt(),15,List.of(),r.foreground(),r.inference(),r.confidence(),List.of(),0,false,"session",d));
    }
    var report=ActivityQualityEvaluation.compareDesktop(records);
    assertEquals(1,report.rows().getFirst().visionCalls());assertEquals(1000,report.rows().getFirst().processingMillis());
    assertThrows(IllegalArgumentException.class,()->ActivityQualityEvaluation.compareDesktop(List.of(a,a)));
  }
}
