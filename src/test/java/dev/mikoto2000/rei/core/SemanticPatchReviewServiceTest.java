package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

class SemanticPatchReviewServiceTest {
  @Test void lostStartedReviewCanBeInspectedWithoutReplayOrProof()throws Exception{var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("review.db"));var service=new SemanticPatchReviewService(ds,Clock.systemUTC(),false,(r,d)->snapshot(),(r,q,d)->{throw new AssertionError("simulated lost operation");},(r,s,d)->{throw new AssertionError();},(input,run,d)->{throw new AssertionError();});assertThrows(AssertionError.class,()->service.review(owner(),request(false),null));var rows=service.list(owner());assertEquals(1,rows.size());var inspection=service.inspect(owner(),rows.getFirst().id());assertEquals("UNKNOWN",inspection.status());assertNull(inspection.sha256());assertFalse(inspection.completed());assertThrows(IllegalStateException.class,()->service.review(owner(),request(false),null));assertThrows(IllegalArgumentException.class,()->service.inspect(new AgentRunContext("foreign","foreign",root,project),inspection.id()));}
  @TempDir Path root;final String project=UUID.randomUUID().toString();final AtomicInteger models=new AtomicInteger();
  String diffOverride;boolean stale;int driftAfter=Integer.MAX_VALUE;final AtomicInteger captures=new AtomicInteger();
  AgentRunContext owner(){return new AgentRunContext("run","session",root,project);}
  dev.mikoto2000.rei.core.stagnation.RunExecutionContext run(){var run=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.class);org.mockito.Mockito.when(run.runContext()).thenReturn(owner());org.mockito.Mockito.when(run.userRequest()).thenReturn("Implement the required value");org.mockito.Mockito.when(run.sharedLlmReservation()).thenReturn(new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation(){public boolean tryReserve(){return true;}public int remaining(){return 2;}});return run;}
  SelfPatchReviewService.Snapshot snapshot(){return new SelfPatchReviewService.Snapshot("a".repeat(64),List.of("A.java","ATest.java"),List.of(),true,List.of());}
  void write(String path,String content)throws Exception{Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,content);}
  void report(String test,boolean skipped)throws Exception{write("target/TEST.xml","<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\""+(skipped?1:0)+"\"><testcase classname=\"ATest\" name=\""+test+"\">"+(skipped?"<skipped/>":"")+"</testcase></testsuite>");}
  SemanticPatchReviewService.Request request(boolean semantic){return new SemanticPatchReviewService.Request("fixture-test",10,List.of(new SemanticPatchReviewService.Requirement("R1","returns the required value",List.of("A.java","ATest.java"),List.of("ATest#value"))),List.of("A.java","ATest.java"),List.of("target/TEST.xml"),semantic);}
  SemanticPatchReviewService service(boolean semanticEnabled,boolean skipped,String test,String source){
    var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("review.db"));
    return new SemanticPatchReviewService(ds,Clock.systemUTC(),semanticEnabled,(r,d)->captures.incrementAndGet()>driftAfter?new SelfPatchReviewService.Snapshot("b".repeat(64),snapshot().changedFiles(),List.of(),true,List.of()):snapshot(),(r,q,d)->{
      try{report(test,skipped);if(stale)Files.setLastModifiedTime(root.resolve("target/TEST.xml"),java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(60)));}catch(Exception error){throw new java.io.IOException(error);}
      var pass=new SelfPatchReviewService.TestObservation("completed",0,false,false,null);
      return new SelfPatchReviewService.Result(root.toString(),"VERIFIED_CHECKS",snapshot().version(),snapshot().version(),snapshot().changedFiles(),pass,new SelfPatchReviewService.Review(true,List.of(),List.of()),pass,List.of(),List.of());
    },(r,s,d)->new GitPatchInspector.Material(diffOverride==null?"diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n+"+source.replace("\n","\n+")+"\n":diffOverride,Map.of("A.java",source,"ATest.java","class ATest {}")),(input,run,d)->{
      models.incrementAndGet();return new SemanticPatchReviewService.Verdict("MATCH",Map.of("R1","PASS"),Map.of("requirements","PASS","extraChanges","PASS","security","PASS","tests","PASS","hygiene","PASS","compatibility","PASS"));
    });
  }
  @Test void deterministicEvidenceAndOptionalReviewAreSavedWithoutClaimingProof()throws Exception {
    var receipt=service(true,false,"value","class A {}").review(owner(),request(true),run());
    assertEquals("REVIEWED_CHECKS",receipt.status());assertEquals(1,models.get());assertEquals(64,receipt.sha256().length());assertEquals("ATest#value",receipt.detail().tests().getFirst().passedTests().getFirst());
    var restored=service(false,false,"other","ignored").get(owner(),receipt.id(),receipt.sha256());assertEquals(receipt,restored);assertFalse(restored.detail().truthVerified());
    assertThrows(IllegalArgumentException.class,()->service(false,false,"value","ignored").get(new AgentRunContext("other","other",root,project),receipt.id(),receipt.sha256()));
  }
  @Test void positiveModelCannotOverrideMissingRequiredTestSkippedTestOrAddedTodo()throws Exception {
    for(var data:List.of(List.of("other","false","class A {}"),List.of("value","true","class A {}"),List.of("value","false","class A { // TODO implement\n}"))){
      var service=service(true,Boolean.parseBoolean(data.get(1)),data.get(0),data.get(2));var receipt=service.review(new AgentRunContext(UUID.randomUUID().toString(),"session",root,project),request(true),null);
      assertEquals("FIX_REQUIRED",receipt.status());assertFalse(receipt.detail().checks().isEmpty());
    }
    assertEquals(0,models.get());
  }
  @Test void unapprovedExtraFilesAndUncoveredRequirementsCannotPass()throws Exception {
    var original=request(true);var narrow=new SemanticPatchReviewService.Request(original.testCommand(),10,original.requirements(),List.of("A.java"),original.testReports(),true);
    assertEquals("FIX_REQUIRED",service(true,false,"value","class A {}").review(owner(),narrow,null).status());assertEquals(0,models.get());
  }
  @Test void disabledSemanticReviewAndDuplicateAttemptNeverInvokeProviderOrCommandAgain()throws Exception {
    var service=service(false,false,"value","class A {}");var receipt=service.review(owner(),request(true),null);assertEquals("SEMANTIC_UNAVAILABLE",receipt.status());assertEquals(0,models.get());
    var repeated=service.review(owner(),request(true),null);assertEquals(receipt,repeated);
  }
  @Test void hygieneDistinguishesLiteralExamplesFromRealCommentAnnotationsAndEmptyCatch()throws Exception {
    assertEquals("DETERMINISTIC_CHECKS_ONLY",service(false,false,"value","class A { String example=\"TODO @Disabled catch(Exception e) {} permitAll()\"; }").review(owner(),request(false),null).status());
    for(String source:List.of("@Disabled class A {}","class A { void f(){ try { work(); } catch(Exception e) {} } }")){
      assertEquals("FIX_REQUIRED",service(false,false,"value",source).review(new AgentRunContext(UUID.randomUUID().toString(),"session",root,project),request(false),null).status());
    }
  }
  @Test void toolEntryKeepsArbitraryCommandAuthorityAndReadOnlyReceiptLookup()throws Exception {
    var tools=new Tools();tools.setPatchReviews(service(false,false,"value","class A {}"));
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner())){var receipt=tools.reviewPatchRequirements(request(false),null);assertEquals(receipt,tools.getPatchRequirementReview(receipt.id(),receipt.sha256()));assertTrue(tools.inspectPatchRequirementReview(receipt.id()).completed());assertEquals(receipt.id(),tools.listPatchRequirementReviews().getFirst().id());}
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null));assertTrue(policy.capabilities("reviewPatchRequirements").contains(dev.mikoto2000.rei.core.policy.ActionCapability.EXECUTE));assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),policy.capabilities("getPatchRequirementReview"));assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),policy.capabilities("inspectPatchRequirementReview"));assertEquals(Set.of(dev.mikoto2000.rei.core.policy.ActionCapability.READ),policy.capabilities("listPatchRequirementReviews"));
  }
  @Test void unmappedDiffHeaderCannotSilentlySkipHygiene()throws Exception {
    diffOverride="diff --git a/A.java b/A.java\n--- a/A.java\n+++ \"b/A.java\"\n@@ -1 +1 @@\n+// TODO finish\n";
    assertEquals("FIX_REQUIRED",service(true,false,"value","class A {}").review(owner(),request(true),run()).status());assertEquals(0,models.get());
  }
  @Test void staleReportAndPatchChangedDuringJudgementDoNotComplete()throws Exception {
    stale=true;assertEquals("FIX_REQUIRED",service(true,false,"value","class A {}").review(owner(),request(true),run()).status());assertEquals(0,models.get());
    stale=false;captures.set(0);driftAfter=2;var result=service(true,false,"value","class A {}").review(new AgentRunContext("other","session",root,project),request(false),null);assertEquals("FIX_REQUIRED",result.status());assertTrue(result.detail().checks().stream().anyMatch(check->check.code().equals("PATCH_CHANGED")));
  }
}
