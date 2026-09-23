package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityEvidenceClassifierTest {
  @Test void representativeDesktopFixturesKeepBackgroundOutOfPrimary() throws Exception {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    try(var input=getClass().getResourceAsStream("/activity/evidence-first-fixtures.json")) {
      for(var fixture:mapper.readTree(input)) {
        var foreground=mapper.treeToValue(fixture.get("foreground"),ForegroundWindow.class);
        var visible=mapper.readerForListOf(ActivityEvidence.VisibleWindow.class).<List<ActivityEvidence.VisibleWindow>>readValue(fixture.get("visibleWindows"));
        var evidence=new ActivityEvidence(NOW,foreground,visible,"rei","p",List.of(),null);
        var result=new ActivityClassifier().classify(evidence);
        assertEquals(fixture.get("expectedFallback").asBoolean(),result.confidence()<.8);
        var record=new ActivityRecord("fixture",NOW,60,List.of(),foreground,result.inference(),result.confidence(),List.of(),0,false);
        var primary=new ActivityRolePolicy().classify(record).primary();
        assertEquals(fixture.get("expectedPrimary").asText(),primary==null?"unknown":primary.type());
        if(primary!=null)assertEquals(3,result.inference().activities().size());
      }
    }
  }
  static final Instant NOW=Instant.parse("2026-09-23T09:00:00Z");
  static ForegroundWindow window(String process,String title) {return new ForegroundWindow(process,1,title,"foreground",new ActivityRecord.Bounds(0,0,800,600));}
  static ActivityEvidence evidence(String process,String title) {
    return new ActivityEvidence(NOW,window(process,title),List.of(),"","",List.of(),null);
  }
  @Test void vscodeWithProjectIsEvidenceOnly() {
    var c=new ActivityClassifier().classify(evidence("Code.exe","BehaviorEvaluator.java - rei - Visual Studio Code"));
    assertEquals("development",c.inference().activities().getFirst().type());
    assertEquals("rei",c.inference().activities().getFirst().projectCandidate());assertTrue(c.confidence()>=.8);
  }
  @Test void knownBrowserTitlesHaveHighConfidenceWhileGenericBrowserDoesNot() {
    var classifier=new ActivityClassifier();
    var x=classifier.classify(evidence("Firefox","ホーム / X"));
    assertEquals("social",x.inference().activities().getFirst().type());assertEquals("X",x.inference().activities().getFirst().service());assertTrue(x.confidence()>=.8);
    var youtube=classifier.classify(evidence("Firefox","Some Video - YouTube"));
    assertEquals("media",youtube.inference().activities().getFirst().type());assertEquals("Some Video",youtube.inference().activities().getFirst().contentTitle());
    assertTrue(classifier.classify(evidence("Firefox","Mozilla Firefox")).confidence()<.5);
    assertTrue(classifier.classify(evidence("Unknown.exe","Some Video - YouTube")).confidence()<.8);
  }
  @Test void visibleCandidatesNeverOverrideForegroundAndHiddenWindowsAreExcluded() {
    var visible=List.of(new ActivityEvidence.VisibleWindow(window("Firefox","ホーム / X"),true,false,false,"m1"),
        new ActivityEvidence.VisibleWindow(window("Firefox","Some Video - YouTube"),true,true,false,"m1"));
    var evidence=new ActivityEvidence(NOW,window("Code","main.java - rei - Visual Studio Code"),visible,"rei","p1",List.of(),null);
    var c=new ActivityClassifier().classify(evidence);
    assertEquals(2,c.inference().activities().size());assertEquals("development",c.inference().activities().getFirst().type());
    assertEquals("social",c.inference().activities().getLast().type());
  }
  @Test void projectContextAloneDoesNotTurnSocialIntoDevelopment() {
    var e=new ActivityEvidence(NOW,window("Firefox","ホーム / X"),List.of(),"rei","p1",List.of(),null);
    var c=new ActivityClassifier().classify(e);assertEquals("",c.inference().activities().getFirst().projectCandidate());assertEquals("social",c.inference().activities().getFirst().type());
  }
}
