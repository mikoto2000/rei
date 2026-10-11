package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlanReviewSemanticValidatorTest {
  @Test void duplicateFindingsAndUnsupportedLocationsRemainUnsupported() {
    var parser=new SubAgentResultParser();
    var result=new PlanReviewSemanticValidator().validate(parser.parse("{\"findings\":[{\"message\":\"x\",\"location\":\"other.md:1\"},{\"message\":\"x\",\"location\":\"other.md:1\"}],\"references\":[{\"path\":\"design.md\"}],\"unverified\":[]}"));
    assertEquals(PlanReviewSemanticValidator.Support.UNSUPPORTED,result.support());
    assertFalse(result.truthVerified());assertFalse(result.issues().isEmpty());
  }
  @Test void traceableFindingsArePartialSupportRatherThanTruth() {
    var result=new PlanReviewSemanticValidator().validate(new SubAgentResultParser().parse("{\"findings\":[{\"message\":\"x\",\"location\":\"design.md:1\"}],\"references\":[{\"path\":\"design.md\"}],\"unverified\":[]}"));
    assertEquals(PlanReviewSemanticValidator.Support.PARTIALLY_SUPPORTED,result.support());assertFalse(result.truthVerified());
  }
}
