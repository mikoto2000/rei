package dev.mikoto2000.rei.activity.behavior;
import dev.mikoto2000.rei.activity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BehaviorDispositionTest {
  @Test void explicitDispositionOverridesCategoryAndLegacyStillWorks() {
    for(var disposition:EntertainmentDisposition.values()) {
      for(String category:List.of("media","research")) {
        var r=BehaviorEvaluatorTest.record(0,600,category);
        var diagnostics=new ClassificationDiagnostics(List.of("test"),"test","user",new ActivityFieldConfidence(.9,1,.9,0,0),true,false,"CLASSIFICATION_USABLE",List.of(),disposition,.9,"test","user","MATCHED");
        var detection=new ActivityRecord.Detection(null,List.of(),false,"EVIDENCE_ONLY","FINAL",Map.of(),"test",diagnostics.confidence(),List.of(),diagnostics);
        var updated=new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),r.inference(),r.confidence(),r.screenshotReferences(),r.changeAmount(),r.duplicate(),r.continuityId(),detection);
        assertEquals(disposition==EntertainmentDisposition.ENTERTAINMENT?600:0,BehaviorEvaluatorTest.assess(600,updated).windows().getFirst().entertainmentObservedSeconds());
      }
    }
    assertEquals(600,BehaviorEvaluatorTest.assess(600,BehaviorEvaluatorTest.record(0,600,"media")).windows().getFirst().entertainmentObservedSeconds());
  }
}
