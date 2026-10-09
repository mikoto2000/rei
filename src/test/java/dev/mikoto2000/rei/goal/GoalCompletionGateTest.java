package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.artifact.Artifact;

class GoalCompletionGateTest {
  @TempDir Path root;GoalRepository goals;FileGoalVerifier verifier;GoalCompletionGate gate;
  String version="a".repeat(64);SemanticPatchReviewService.Receipt review;Artifact artifact;
  AgentRunContext owner(){return new AgentRunContext("source","session",root,"project");}
  String sha(String text)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  GoalRepository.Goal goal()throws Exception{Files.writeString(root.resolve("out.txt"),"correct");return goals.create(owner(),"produce exact result","out.txt",sha("correct"),2,5);}
  @BeforeEach void setup(){goals=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());verifier=new FileGoalVerifier();configure(false);}
  void configure(boolean force){gate=new GoalCompletionGate(goals,(owner,ref)->{if(review==null||!review.id().equals(ref.id())||!review.sha256().equals(ref.sha256())||!owner.conversationId().equals(review.detail().session()))throw new IllegalArgumentException("no receipt");return review;},(owner,ref)->{if(artifact==null||!artifact.artifactId().equals(ref.id())||!artifact.sha256().equals(ref.sha256()))throw new IllegalArgumentException("no artifact");return artifact;},(r,d)->new SelfPatchReviewService.Snapshot(version,List.of("out.txt"),List.of(),true,List.of()),Clock.systemUTC(),force);verifier.setCompletionGate(gate);}
  GoalCompletionGate.Definition definition(boolean semantic)throws Exception{return new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),new GoalCompletionGate.RequiredTests(sha("fixture-test"),List.of("Fixture#value")),List.of(),List.of(),new GoalCompletionGate.ReviewGate(semantic,List.of(new SemanticPatchReviewService.Requirement("R1","produce exact result",List.of("out.txt"),List.of("Fixture#value")))));}
  void receipt(String status)throws Exception{
    String report="<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"Fixture\" name=\"value\"/></testsuite>";Files.writeString(root.resolve("TEST.xml"),report);
    var pass=new SelfPatchReviewService.TestObservation("completed",0,false,false,null);var verification=new SelfPatchReviewService.Result(root.toString(),"VERIFIED_CHECKS",version,version,List.of("out.txt"),pass,new SelfPatchReviewService.Review(true,List.of(),List.of()),pass,List.of(),List.of());
    var fact=new SemanticPatchReviewService.TestFact("TEST.xml",sha(report),Instant.now(),List.of("Fixture#value"),new TestReportDiagnosisService.Counts(1,0,0,0),true);
    review=new SemanticPatchReviewService.Receipt(UUID.randomUUID().toString(),status,"b".repeat(64),new SemanticPatchReviewService.Detail("project",root.toString(),"session","source",status,Instant.now(),sha("fixture-test"),definition(false).reviewGate().requirements(),List.of(new SemanticPatchReviewService.FileFact("out.txt",sha("correct"),true)),List.of(fact),List.of(),verification,null,false,List.of()));
  }
  GoalCompletionGate.Proof proof(){return new GoalCompletionGate.Proof(new GoalCompletionGate.Reference(review.id(),review.sha256()),List.of());}
  @Test void forceAllRejectsLegacyGoalButDefaultPreservesItsExistingFilePredicate()throws Exception{
    var goal=goal();assertTrue(verifier.verify(goal).satisfied());configure(true);assertEquals("completion_definition_missing",verifier.verify(goal).reason());
  }
  @Test void missingReviewCannotCompleteAndPersistedProofMustMatchCurrentPatchReportsAndCommand()throws Exception{
    var goal=goal();goals.defineCompletion(owner(),goal.id(),definition(false));assertEquals("completion_evidence_missing",verifier.verify(goals.get("project",goal.id())).reason());receipt("DETERMINISTIC_CHECKS_ONLY");gate.attach(owner(),goal.id(),proof(),true);
    var restored=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());assertNotNull(restored.get("project",goal.id()).completion());assertTrue(verifier.verify(restored.get("project",goal.id())).satisfied());
    Files.writeString(root.resolve("TEST.xml"),"<testsuite/>");assertFalse(verifier.verify(goals.get("project",goal.id())).satisfied());receipt("DETERMINISTIC_CHECKS_ONLY");gate.attach(owner(),goal.id(),proof(),true);version="c".repeat(64);assertEquals("completion_review_stale",verifier.verify(goals.get("project",goal.id())).reason());
  }
  @Test void semanticGateDoesNotAcceptDeterministicOnlyAndCannotBeRedefinedByRunningModel()throws Exception{
    var goal=goal();goals.defineCompletion(owner(),goal.id(),definition(true));receipt("DETERMINISTIC_CHECKS_ONLY");gate.attach(owner(),goal.id(),proof(),true);assertEquals("completion_review_not_verified",verifier.verify(goals.get("project",goal.id())).reason());
    var claim=goals.claim("project",goal.id());String run=goals.beginAttempt(claim);assertThrows(IllegalStateException.class,()->goals.defineCompletion(owner(),goal.id(),definition(false)));assertThrows(IllegalArgumentException.class,()->gate.attach(owner(),goal.id(),proof(),false));assertThrows(IllegalArgumentException.class,()->gate.attach(new AgentRunContext(run,"foreign",root,"project"),goal.id(),proof(),false));
  }
  @Test void artifactsAndPredicatesAreVerifiedAndUnavailableEvidenceCannotPass()throws Exception{
    var goal=goal();Files.writeString(root.resolve("state.json"),"{\"done\":false}");var requirement=new GoalCompletionGate.ArtifactRequirement("result.txt","text/plain",sha("correct"));
    var definition=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,List.of(requirement),List.of(new GoalRepository.FileCriterion("state.json",null,"/done","true")),null);goals.defineCompletion(owner(),goal.id(),definition);
    artifact=new Artifact(UUID.randomUUID().toString(),"RUN","project","session","source",null,"text/plain","result.txt",7,sha("correct"),Instant.now(),Instant.now().plusSeconds(60),"opaque","AVAILABLE");gate.attach(owner(),goal.id(),new GoalCompletionGate.Proof(null,List.of(new GoalCompletionGate.Reference(artifact.artifactId(),artifact.sha256()))),true);
    assertFalse(verifier.verify(goals.get("project",goal.id())).satisfied());Files.writeString(root.resolve("state.json"),"{\"done\":true}");assertTrue(verifier.verify(goals.get("project",goal.id())).satisfied());artifact=null;assertFalse(verifier.verify(goals.get("project",goal.id())).satisfied());
  }
  @Test void existingLoopVerifyAndPostChatCompletionBothUseGate()throws Exception{
    var goal=goal();goals.defineCompletion(owner(),goal.id(),definition(false));var loop=new GoalLoopService(goals,verifier,(claim,run,done)->done.accept(new GoalLoopService.Outcome(ChatExecutionResult.success("done",false))),new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));
    assertEquals("READY",loop.verify("project",goal.id()).goal().status());assertEquals("BLOCKED",loop.run("project",goal.id()).status());assertNotEquals("COMPLETED",goals.get("project",goal.id()).status());receipt("DETERMINISTIC_CHECKS_ONLY");gate.attach(owner(),goal.id(),proof(),true);assertEquals("COMPLETED",loop.verify("project",goal.id()).goal().status());assertEquals("completion_gate_verified",goals.get("project",goal.id()).reason());
  }
  @Test void currentGoalRunCanAttachEvidenceAndCompletionObservesItWithoutAnotherModelRun()throws Exception{
    var goal=goal();goals.defineCompletion(owner(),goal.id(),definition(false));receipt("DETERMINISTIC_CHECKS_ONLY");var count=new java.util.concurrent.atomic.AtomicInteger();
    var loop=new GoalLoopService(goals,verifier,(claim,run,done)->{count.incrementAndGet();try{gate.attach(new AgentRunContext(run,"session",root,"project"),goal.id(),proof(),false);}catch(Exception error){throw new RuntimeException(error);}done.accept(new GoalLoopService.Outcome(ChatExecutionResult.success("done",false)));},new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));
    assertEquals("COMPLETED",loop.run("project",goal.id()).status());assertEquals(1,count.get());assertEquals(1,goals.get("project",goal.id()).attempts());
  }
  @Test void commandMismatchAndUntrustedCompletionJsonCannotWeakenDefinition()throws Exception{
    var goal=goal();var original=definition(false);var different=new GoalCompletionGate.Definition(original.completionEvidence(),new GoalCompletionGate.RequiredTests(sha("different-test"),List.of("Fixture#value")),List.of(),List.of(),original.reviewGate());goals.defineCompletion(owner(),goal.id(),different);receipt("DETERMINISTIC_CHECKS_ONLY");gate.attach(owner(),goal.id(),proof(),true);assertEquals("completion_required_tests_missing",verifier.verify(goals.get("project",goal.id())).reason());
    var json=new com.fasterxml.jackson.databind.ObjectMapper();String encoded=json.writeValueAsString(original);
    for(String invalid:List.of(encoded+" {}",encoded.replace("\"completionEvidence\":","\"unknown\":true,\"completionEvidence\":"),encoded.replace("\"completionEvidence\":","\"completionEvidence\":[],\"completionEvidence\":")))assertThrows(IllegalArgumentException.class,()->GoalRepository.parseCompletion(invalid));
    String commandJson="{\"completionEvidence\":[{\"relativeFile\":\"out.txt\",\"sha256\":\""+sha("correct")+"\"}],\"requiredTests\":{\"testCommand\":\"fixture-test\",\"tests\":[\"Fixture#value\"]}}";
    assertEquals(sha("fixture-test"),GoalRepository.parseCompletion(commandJson).requiredTests().commandSha256());
  }
  @Test void definitionChangeBetweenReadAndCompletionCannotCompleteUsingOldEvidence()throws Exception{
    var goal=goal();var old=goals.defineCompletion(owner(),goal.id(),definition(false));var latest=new GoalCompletionGate.Definition(old.completion().completionEvidence(),null,List.of(new GoalCompletionGate.ArtifactRequirement("required.txt","text/plain",null)),List.of(),null);goals.defineCompletion(owner(),goal.id(),latest);
    assertThrows(IllegalStateException.class,()->goals.verifiedWithoutRun(old,"completion_gate_verified"));assertEquals("READY",goals.get("project",goal.id()).status());
  }
  @Test void shellCreatesGoalAndCompletionAtomicallyAndRejectsMalformedDefinitionWithoutSaving()throws Exception{
    Files.writeString(root.resolve("out.txt"),"correct");var loop=new GoalLoopService(goals,verifier,(claim,run,done)->fail("no dispatch"),new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));loop.setCompletionGate(gate);
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);var context=new dev.mikoto2000.rei.core.project.ProjectContext(UUID.randomUUID().toString(),"fixture",root);org.mockito.Mockito.when(projects.currentContext()).thenReturn(context);org.mockito.Mockito.when(projects.currentSessionId()).thenReturn("session");
    String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(definition(false));var command=new GoalCommand(goals,loop,projects);command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));assertEquals(0,new picocli.CommandLine(command).execute("create","gated","--file","out.txt","--sha256",sha("correct"),"--completion-json",json));assertNotNull(goals.list(context.id()).getFirst().completion());
    command=new GoalCommand(goals,loop,projects);command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));assertEquals(2,new picocli.CommandLine(command).execute("create","invalid","--file","out.txt","--sha256",sha("correct"),"--completion-json","{}"));assertEquals(1,goals.list(context.id()).size());
  }
  @Test void failedSavedTestIsClassifiedAndRepairedThroughExistingLoop()throws Exception{
    var goal=goal();goals.defineCompletion(owner(),goal.id(),definition(false));var calls=new java.util.concurrent.atomic.AtomicInteger();
    var loop=new GoalLoopService(goals,verifier,(claim,run,done)->{
      assertTrue(goals.reserveLlm(claim));
      try{
        if(calls.incrementAndGet()==1){
          String xml="<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"><testcase classname=\"Fixture\" name=\"value\"><failure message=\"wrong result\"/></testcase></testsuite>";Files.writeString(root.resolve("TEST.xml"),xml);
          var failed=new SelfPatchReviewService.TestObservation("completed",1,false,false,null);
          var verified=new SelfPatchReviewService.Result(root.toString(),"INITIAL_TEST_FAILED",version,version,List.of("out.txt"),failed,null,null,List.of("inspect failure"),List.of());
          var fact=new SemanticPatchReviewService.TestFact("TEST.xml",sha(xml),Instant.now(),List.of(),new TestReportDiagnosisService.Counts(1,1,0,0),true);
          review=new SemanticPatchReviewService.Receipt(UUID.randomUUID().toString(),"FIX_REQUIRED","b".repeat(64),new SemanticPatchReviewService.Detail("project",root.toString(),"session",run,"FIX_REQUIRED",Instant.now(),sha("fixture-test"),definition(false).reviewGate().requirements(),List.of(new SemanticPatchReviewService.FileFact("out.txt",sha("correct"),true)),List.of(fact),List.of(),verified,null,false,List.of()));
        }else receipt("DETERMINISTIC_CHECKS_ONLY");
        gate.attach(new AgentRunContext(run,"session",root,"project"),goal.id(),proof(),false);
      }catch(Exception error){throw new RuntimeException(error);}
      done.accept(new GoalLoopService.Outcome(ChatExecutionResult.success("done",false)));
    },new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(e->{},Clock.systemUTC()));
    assertEquals("COMPLETED",loop.run("project",goal.id()).status());assertEquals(2,calls.get());
    assertEquals("completion_tests_failed",goals.attempts("project",goal.id()).getFirst().reason());
    assertTrue(verifier.verify(goals.get("project",goal.id())).satisfied());
  }}
