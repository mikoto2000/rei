package dev.mikoto2000.rei.activity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class ActivityExtractionTest {
  @Test void diagnosticIdentifiesConfidenceConstraintWithoutOutputValues() {
    var error=assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse(VALID.replace("0.86","1.2"),List.of("m1","m2")));
    assertEquals("/confidence:maximum",error.diagnostic());
  }
  @Test void emptyOutputHasDistinctDiagnostic() {
    var error=assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse("   ",List.of("m1")));
    assertEquals("/:empty_output",error.diagnostic());
  }
  @Test void diagnosticCannotLeakArbitraryPathsOrCodes() {
    var error=new ActivityOutputParser.InvalidOutput(List.of(new ActivityOutputParser.ResultError("/PRIVATE_TITLE\nsecret","PRIVATE_PAYLOAD")));
    assertEquals("/:validation_error",error.diagnostic());
  }
  @Test void diagnosticIdentifiesActivityField() {
    var error=assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse(VALID.replace("\"service\":\"X\"","\"service\":null"),List.of("m1","m2")));
    assertEquals("/activities/0/service:type",error.diagnostic());
  }
  static final String VALID = """
      {"summary":"X and YouTube are visible", "confidence":0.86,"activities":[
        {"monitor":"m1","type":"social","application":"Firefox","service":"X","contentTitle":"","projectCandidate":""},
        {"monitor":"m2","type":"media","application":"Firefox","service":"YouTube","contentTitle":"Xxx","projectCandidate":""}]}
      """;
  @Test void retainsParallelActivitiesAndConfidence() { var result = new ActivityOutputParser().parse(VALID,List.of("m1","m2")); assertEquals(2,result.inference().activities().size()); assertEquals(.86,result.confidence()); }
  @Test void invalidJsonRejected() { assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse("oops",List.of("m1"))); }
  @Test void invalidConfidenceRejected() { assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse(VALID.replace("0.86","1.2"),List.of("m1","m2"))); }
  @Test void inventedMonitorRejected() { assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse(VALID,List.of("m1"))); }
  @Test void modelCannotReplaceObservation() { assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse(VALID.replace("\"confidence\":0.86", "\"observations\":[],\"confidence\":0.86"),List.of("m1","m2"))); }
  @Test void trailingJsonRejected() { assertThrows(ActivityOutputParser.InvalidOutput.class,()->new ActivityOutputParser().parse(VALID+"{}",List.of("m1","m2"))); }
  @Test void extensibleCategory() { assertEquals("new-category",new ActivityOutputParser().parse(VALID.replace("social","new-category"),List.of("m1","m2")).inference().activities().getFirst().type()); }
}
