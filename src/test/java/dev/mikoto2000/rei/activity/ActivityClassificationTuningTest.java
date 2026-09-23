package dev.mikoto2000.rei.activity;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityClassificationTuningTest {
  @Test void browserFixturesRespectServiceBoundariesAndCategoryUncertainty() throws Exception {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    try(var input=getClass().getResourceAsStream("/activity/classification-tuning-fixtures.json")) {
      for(var fixture:mapper.readTree(input)) {
        var c=new ActivityClassifier().classify(ActivityEvidenceClassifierTest.evidence(fixture.get("process").asText(),fixture.get("title").asText()));
        var a=c.inference().activities().getFirst();String title=fixture.get("title").asText();
        assertEquals(fixture.get("category").asText(),a.type(),title);assertEquals(fixture.get("service").asText(),a.service(),title);
        assertEquals(fixture.get("content").asText(),a.contentTitle(),title);
        assertEquals(fixture.get("skip").asBoolean(),c.usable(.8),title);
      }
    }
  }
  @Test void projectAndContentGapsDoNotRequireVisionButUnknownCategoryDoes() {
    var classifier=new ActivityClassifier();
    for(String title:List.of("X","YouTube")) {
      var c=classifier.classify(ActivityEvidenceClassifierTest.evidence("Firefox",title));
      assertTrue(c.usable(.8));assertFalse(c.complete());assertEquals(0,c.fieldConfidence().project());assertEquals(0,c.fieldConfidence().content());
    }
    var chat=classifier.classify(ActivityEvidenceClassifierTest.evidence("Firefox","ChatGPT"));
    assertEquals(0,chat.fieldConfidence().category());assertTrue(chat.fieldConfidence().service()>=.9);
    assertTrue(chat.fieldConfidence().application()>=.9);assertFalse(chat.usable(.8));assertTrue(chat.partial());
  }
  @Test void genericPullRequestAndRepositoryWordsDoNotIdentifyGithub() {
    for(String title:List.of("Pull Request","repository","OpenAI research notes","YouTube.md")) {
      var c=new ActivityClassifier().classify(ActivityEvidenceClassifierTest.evidence("Firefox",title));
      assertFalse(c.usable(.8));assertEquals("",c.inference().activities().getFirst().service());
    }
  }
  @Test void foregroundConfidenceIsIndependentOfSecondaryConfidence() {
    var fg=ActivityEvidenceClassifierTest.window("Code","a.java - rei - Visual Studio Code");
    var visible=List.of(new ActivityEvidence.VisibleWindow(ActivityEvidenceClassifierTest.window("Firefox","YouTube"),true,false,false,"m2"));
    var c=new ActivityClassifier().classify(new ActivityEvidence(ActivityEvidenceClassifierTest.NOW,fg,visible,"rei","p",List.of(),null));
    assertTrue(c.usable(.8));assertEquals(.95,c.fieldConfidence().category());
    assertEquals(1,c.secondaryConfidence().size());assertTrue(c.secondaryConfidence().getFirst().confidence().category()<c.fieldConfidence().category());
  }
}
