package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ActivityEvidenceDisplayFormatterTest {
  static ActivityRecord record(VisionDiagnostics.Result foreground,VisionDiagnostics.Result background) {
    var r=ActivitySemanticTest.dev(0,"Terminal","X");
    var diagnostics=new ClassificationDiagnostics(List.of("dev-rule"),"dev-rule","user",new ActivityFieldConfidence(.9,1,.8,.7,0),true,false,"CLASSIFICATION_USABLE",List.of(),EntertainmentDisposition.NON_ENTERTAINMENT,.9,"dev","user","rule");
    var d=new ActivityRecord.Detection(null,List.of("FOREGROUND_WINDOW","WINDOW_TITLE"),true,"EVIDENCE_PLUS_VISION","FINAL",Map.of(),"dev-rule",diagnostics.confidence(),List.of(),diagnostics,new VisionDiagnostics(foreground,background));
    return new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),r.inference(),r.confidence(),List.of("private-image"),r.changeAmount(),false,r.continuityId(),d);
  }
  static VisionDiagnostics.Result state(VisionDiagnostics.State state) {return new VisionDiagnostics.Result(state,null);}
  @Test void windowOnly() {
    assertEquals("Window",new ActivityEvidenceDisplayFormatter().evidence(record(state(VisionDiagnostics.State.NOT_ATTEMPTED),state(VisionDiagnostics.State.NOT_ATTEMPTED))));
  }
  @Test void foregroundAndBackgroundUsedAreIndependent() {
    var f=new ActivityEvidenceDisplayFormatter();var used=state(VisionDiagnostics.State.USED);var no=state(VisionDiagnostics.State.NOT_ATTEMPTED);
    assertEquals("Window + Foreground Vision",f.evidence(record(used,no)));
    assertEquals("Window + Background Vision",f.evidence(record(no,used)));
    assertEquals("Window + Foreground Vision + Background Vision",f.evidence(record(used,used)));
  }
  @ParameterizedTest @EnumSource(value=VisionDiagnostics.State.class,names={"ATTEMPTED_FAILED","ATTEMPTED_SUCCEEDED_NOT_USED","ATTEMPTED","UNKNOWN"})
  void unusedImagesNeverBecomeEvidence(VisionDiagnostics.State status) {
    var r=record(new VisionDiagnostics.Result(status,status==VisionDiagnostics.State.ATTEMPTED_FAILED?ActivityVisionFailure.OUTPUT_LIMIT:null),state(VisionDiagnostics.State.NOT_ATTEMPTED));
    var f=new ActivityEvidenceDisplayFormatter();assertEquals("Window",f.evidence(r));
    assertTrue(f.verbose(r).contains(status.name()));
    if(status==VisionDiagnostics.State.ATTEMPTED_FAILED)assertTrue(f.verbose(r).contains("OUTPUT_LIMIT"));
  }
  @Test void verboseShowsSavedDiagnosticsWithoutRawData() {
    var text=new ActivityEvidenceDisplayFormatter().verbose(record(state(VisionDiagnostics.State.USED),state(VisionDiagnostics.State.NOT_ATTEMPTED)));
    for(var value:List.of("confidence:","category=0.9","service=0.8","application=1.0","dev-rule","CLASSIFICATION_USABLE","usable: true","EVIDENCE_PLUS_VISION"))assertTrue(text.contains(value),text);
    assertFalse(text.contains("private-image"));assertFalse(text.contains("積極的"));
  }
  @Test void legacyRecordDoesNotImplyVisionUsage() {
    var f=new ActivityEvidenceDisplayFormatter();var r=ActivitySemanticTest.dev(0,"Terminal","X");
    assertEquals("Unknown",f.evidence(r));assertTrue(f.verbose(r).contains("UNKNOWN"));
  }
}
