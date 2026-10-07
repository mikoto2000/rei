package dev.mikoto2000.rei.activity;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ActivityQualityEvaluationTest {
  @Test void groundedAdviceAndIncorrectLabelsAreEvaluatedStructurally(){var research=new ActivityRecord.Activity("m1","research","Firefox","GitHub","","project-alpha");var good=new ActivityQualityEvaluation.Case("research","healthy","Firefox","GitHub",.9,7200,List.of(research),"project-alpha","work-development",true,"BELOW_TARGET_DECLINING",List.of());var report=ActivityQualityEvaluation.evaluate(List.of(good));assertTrue(report.rows().getFirst().advice());assertEquals(0,report.structuralFailures());var wrong=new ActivityQualityEvaluation.Case("wrong","unknown","Unknown","",.9,7200,List.of(),"invented-project","work-development",true,"BELOW_TARGET",List.of());var mismatch=ActivityQualityEvaluation.evaluate(List.of(wrong));assertEquals(0,mismatch.projectAccuracy());assertEquals(0,mismatch.themeAccuracy());assertTrue(mismatch.structuralFailures()>0);assertFalse(mismatch.rows().getFirst().advice());}
  @Test void anonymousCasesPreserveUnknownGapsAndFactOrigins() throws Exception {
    var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var cases=mapper.readValue(getClass().getResourceAsStream("/evaluation/activity-coaching-quality.json"),ActivityQualityEvaluation.Case[].class);
    var report=ActivityQualityEvaluation.evaluate(List.of(cases));assertEquals(5,report.rows().size());assertEquals(0,report.structuralFailures());assertEquals(1,report.projectAccuracy());assertEquals(1,report.themeAccuracy());assertFalse(report.truthVerified());
    var healthy=report.rows().getFirst();assertEquals("project-alpha",healthy.project());assertEquals("work-development",healthy.theme());assertTrue(healthy.facts().stream().anyMatch(f->f.origin()==ActivityQualityEvaluation.Origin.OBSERVED));assertTrue(healthy.facts().stream().anyMatch(f->f.origin()==ActivityQualityEvaluation.Origin.INFERRED));assertTrue(healthy.facts().stream().anyMatch(f->f.origin()==ActivityQualityEvaluation.Origin.USER_CONFIRMED));
    for(var row:report.rows().subList(1,5)){assertFalse(row.advice());assertTrue(row.facts().stream().noneMatch(f->f.origin()==ActivityQualityEvaluation.Origin.USER_CONFIRMED));}
    assertTrue(report.rows().getLast().unobservedSeconds()>report.rows().getLast().observedSeconds());java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/evaluation"));mapper.writeValue(java.nio.file.Path.of("target/evaluation/activity-coaching-quality.json").toFile(),report);
  }
  @Test void fabricatedConfirmationAndUnboundedFixturesAreRejected(){assertThrows(IllegalArgumentException.class,()->new ActivityQualityEvaluation.ConfirmedFact("project","alpha",""));assertThrows(IllegalArgumentException.class,()->ActivityQualityEvaluation.evaluate(List.of()));}
}
