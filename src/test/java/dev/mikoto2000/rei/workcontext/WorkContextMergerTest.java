package dev.mikoto2000.rei.workcontext;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.workcontext.WorkContext.*;

class WorkContextMergerTest {
  final Instant now=Instant.parse("2026-10-03T00:00:00Z");
  Evidence source(Origin origin) { return new Evidence("s",origin,"session","run","run",null,null,null,now,now,"evidence"); }
  WorkContextCandidate add(String text,Kind kind,Status status) {
    return new WorkContextCandidate("ADD",null,kind,text,"reason",status,List.of("s"));
  }
  WorkContext apply(WorkContext old,String run,List<WorkContextCandidate> changes,Origin origin) {
    return new WorkContextMerger().merge("A",old,run,changes,Map.of("s",source(origin)),null,now);
  }
  @Test void unfinishedItemsSurviveAndRepeatedRunsAndExactDuplicatesDoNotAccumulate() {
    var a=apply(null,"1",List.of(add("verify Native",Kind.PENDING,Status.OPEN)),Origin.ASSISTANT);
    var b=apply(a,"2",List.of(add("verify Native",Kind.PENDING,Status.OPEN),add("new task",Kind.NEXT_ACTION,Status.OPEN)),Origin.USER);
    assertEquals(2,b.items().size());
    assertEquals(b,apply(b,"2",List.of(add("duplicate run",Kind.PENDING,Status.OPEN)),Origin.USER));
    assertEquals(2,apply(b,"3",List.of(),Origin.USER).items().size());
  }
  @Test void assistantCompletionIsReportedAndVerificationRemainsUnconfirmed() {
    var a=apply(null,"1",List.of(add("implemented",Kind.COMPLETED_WORK,Status.COMPLETED),add("tests passed",Kind.VERIFICATION,Status.COMPLETED)),Origin.ASSISTANT);
    assertEquals(Origin.ASSISTANT,a.items().getFirst().evidence().getFirst().origin());
    assertEquals(Status.UNCONFIRMED,a.items().get(1).status());
  }
  @Test void explicitCorrectionIsProtectedFromOlderInferenceAndHistoryRetainsStableId() {
    var a=apply(null,"1",List.of(add("old policy",Kind.DECISION,Status.OPEN)),Origin.INFERENCE);
    var id=a.items().getFirst().id();
    var b=apply(a,"2",List.of(new WorkContextCandidate("CORRECT",id,Kind.DECISION,"new policy","user reason",Status.OPEN,List.of("s"))),Origin.USER);
    var c=apply(b,"3",List.of(new WorkContextCandidate("CORRECT",id,Kind.DECISION,"old policy","guess",Status.OPEN,List.of("s"))),Origin.INFERENCE);
    assertEquals(id,c.items().getFirst().id()); assertEquals("new policy",c.items().getFirst().text());
    assertEquals("old policy",a.items().getFirst().text());
  }
  @Test void supersessionWithdrawAndReopenAreExplicitAndUnknownEvidenceIsRejected() {
    var a=apply(null,"1",List.of(add("policy",Kind.DECISION,Status.OPEN)),Origin.USER);
    var id=a.items().getFirst().id();
    var b=apply(a,"2",List.of(new WorkContextCandidate("SUPERSEDE",id,Kind.DECISION,"replacement","reason",Status.OPEN,List.of("s"))),Origin.USER);
    assertEquals(Status.SUPERSEDED,b.items().getFirst().status()); assertNotNull(b.items().getFirst().supersededBy());
    assertThrows(IllegalArgumentException.class,()->apply(a,"3",List.of(new WorkContextCandidate("ADD",null,Kind.PENDING,"bad","",Status.OPEN,List.of("invented"))),Origin.USER));
  }
  @Test void proposalDerivedFromUserQuestionRemainsInferenceAndCannotClaimToolVerification() {
    var proposal=new WorkContextCandidate("ADD",null,Kind.NEXT_ACTION,"try a new design","proposal",Status.OPEN,List.of("s"),Origin.INFERENCE);
    var a=apply(null,"1",List.of(proposal),Origin.USER);
    assertEquals(Origin.INFERENCE,a.items().getFirst().certainty());
    var fabricated=new WorkContextCandidate("ADD",null,Kind.VERIFICATION,"tests passed","",Status.COMPLETED,List.of("s"),Origin.TOOL);
    assertThrows(IllegalArgumentException.class,()->apply(null,"2",List.of(fabricated),Origin.ASSISTANT));
  }
  @Test void laterToolVerificationUpdatesCertaintyButOlderUserEvidenceCannotReverseCorrection() {
    var a=apply(null,"1",List.of(add("tests passed",Kind.VERIFICATION,Status.COMPLETED)),Origin.ASSISTANT);
    var id=a.items().getFirst().id();
    var verified=apply(a,"2",List.of(new WorkContextCandidate("STATUS",id,Kind.VERIFICATION,"tests passed","tool result",Status.COMPLETED,List.of("s"))),Origin.TOOL);
    assertEquals(Status.COMPLETED,verified.items().getFirst().status());assertEquals(Origin.TOOL,verified.items().getFirst().certainty());
    var correction=apply(verified,"3",List.of(new WorkContextCandidate("CORRECT",id,Kind.VERIFICATION,"acceptance pending","",Status.OPEN,List.of("s"))),Origin.USER);
    var older=new Evidence("old",Origin.USER,"session","old-run","old-run",null,null,null,now.minusSeconds(60),now,"old completion");
    var c=new WorkContextMerger().merge("A",correction,"4",List.of(new WorkContextCandidate("STATUS",id,Kind.VERIFICATION,"tests passed","old",Status.COMPLETED,List.of("old"))),Map.of("old",older),null,now);
    assertEquals(Status.OPEN,c.items().getFirst().status());
  }
  @Test void conflictEvidenceIsRetainedWithoutOverwritingAnExplicitUserCorrection() {
    var a=apply(null,"1",List.of(add("policy",Kind.DECISION,Status.OPEN)),Origin.USER);var id=a.items().getFirst().id();
    var b=apply(a,"2",List.of(new WorkContextCandidate("CORRECT",id,Kind.DECISION,"accepted policy","user correction",Status.OPEN,List.of("s"))),Origin.USER);
    var c=apply(b,"3",List.of(new WorkContextCandidate("CONFLICT",id,Kind.DECISION,"contrary report","new conflicting evidence",Status.OPEN,List.of("s"))),Origin.ASSISTANT);
    assertEquals("accepted policy",c.items().getFirst().text());assertEquals(Status.OPEN,c.items().getFirst().status());
    assertEquals(2,c.items().size());assertEquals(Status.UNCONFIRMED,c.items().getLast().status());
  }
}
