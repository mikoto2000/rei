package dev.mikoto2000.rei.goal;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.artifact.Artifact;

class GoalRequirementDeliveryTest {
 @TempDir Path directory;
 Path root; GoalRepository goals; FileGoalVerifier verifier; GoalCompletionGate gate; Artifact artifact;
 AgentRunContext owner(){return new AgentRunContext("human","session",root,"project");}
 String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
 @BeforeEach void setup()throws Exception {
  root=directory.toRealPath();goals=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());
  gate=new GoalCompletionGate(goals,(o,r)->{throw new java.io.IOException("no review");},(o,r)->artifact,(r,d)->{throw new java.io.IOException("no patch");},Clock.systemUTC(),false);
  verifier=new FileGoalVerifier();verifier.setCompletionGate(gate);Files.writeString(root.resolve("out.txt"),"correct");
 }
 GoalRepository.Goal create(GoalCompletionGate.Definition definition)throws Exception {
  return goals.create(owner(),"requirements",List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),definition,3,3);
 }
 @Test void namedMandatoryConditionsBlockButOptionalConditionsDoNotAndRestoreAfterRestart()throws Exception {
  var definition=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,List.of(),List.of(),null,
    List.of(new GoalCompletionGate.Requirement("R1","mandatory check",true,new GoalRepository.FileCriterion("state.json",null,"/done","true")),
      new GoalCompletionGate.Requirement("R2","optional file",false,new GoalRepository.FileCriterion("optional.txt",sha("optional")))));
  var goal=create(definition);assertEquals("completion_requirement_unmet",verifier.verify(goal).reason());
  Files.writeString(root.resolve("state.json"),"{\"done\":true}");assertTrue(verifier.verify(goals.get("project",goal.id())).satisfied());
  var restarted=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());
  assertEquals(definition,restarted.get("project",goal.id()).completion());assertTrue(verifier.verify(restarted.get("project",goal.id())).satisfied());
 }
 @Test void savedArtifactDoesNotProveHumanHandoverAndModelCannotAttachIt()throws Exception {
  artifact=new Artifact(UUID.randomUUID().toString(),"RUN","project","session","source",null,"text/plain","result.txt",7,sha("correct"),Instant.now(),Instant.now().plusSeconds(600),"opaque","AVAILABLE");
  var definition=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,
      List.of(new GoalCompletionGate.ArtifactRequirement("result.txt","text/plain",sha("correct"),true)),List.of(),null);
  var goal=create(definition);var reference=new GoalCompletionGate.Reference(artifact.artifactId(),artifact.sha256());
  gate.attach(owner(),goal.id(),new GoalCompletionGate.Proof(null,List.of(reference)),true);
  assertEquals("completion_delivery_pending",verifier.verify(goals.get("project",goal.id())).reason());
  var claim=goals.claim("project",goal.id());String run=goals.beginAttempt(claim);
  assertThrows(IllegalArgumentException.class,()->gate.attach(new AgentRunContext(run,"session",root,"project"),goal.id(),new GoalCompletionGate.Proof(null,List.of(reference),List.of(reference)),false));
  goals.stop(claim,"BLOCKED","completion_delivery_pending");
  gate.attach(owner(),goal.id(),new GoalCompletionGate.Proof(null,List.of(reference),List.of(reference)),true);
  assertTrue(verifier.verify(goals.get("project",goal.id())).satisfied());
  assertThrows(IllegalArgumentException.class,()->GoalRepository.parseCompletionProof("{\"artifacts\":[],\"deliveredArtifacts\":[{\"id\":\""+reference.id()+"\",\"sha256\":\""+reference.sha256()+"\"}]}"));
 }
 @Test void invalidRequirementCannotWeakenVerification()throws Exception {
  var base=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,List.of(),List.of(),null,
      List.of(new GoalCompletionGate.Requirement("R1","exists only",true,new GoalRepository.FileCriterion("out.txt",null))));
  assertThrows(IllegalArgumentException.class,()->create(base));
 }
 @Test void runningVerificationAndRepairAreDistinguishedWithoutChangingClaimsOrBudgets()throws Exception {
  var goal=goals.create(owner(),"repair","state.json",sha("correct"),3,3);
  var seen=new ArrayList<String>();var attempts=new java.util.concurrent.atomic.AtomicInteger();
  var loop=new GoalLoopService(goals,verifier,(claim,run,done)->{
    seen.add(goals.completionPhase("project",goal.id()));assertTrue(goals.reserveLlm(claim));
    try{Files.writeString(root.resolve("state.json"),attempts.incrementAndGet()==1?"wrong":"correct");}catch(Exception e){throw new RuntimeException(e);}
    done.accept(new GoalLoopService.Outcome(dev.mikoto2000.rei.core.chat.ChatExecutionResult.success("done",false)));
  },new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(e->{},Clock.systemUTC()));
  assertEquals("COMPLETED",loop.run("project",goal.id()).status());
  assertEquals(List.of("RUNNING","REPAIRING"),seen);assertEquals("COMPLETED",goals.completionPhase("project",goal.id()));
  assertEquals(2,goals.get("project",goal.id()).llmCallsUsed());
  var restored=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());
  assertEquals("COMPLETED",restored.completionPhase("project",goal.id()));
 } @Test void sameFilenameCannotSubstituteDifferentRevisionForHandover()throws Exception {
  var correct=new Artifact(UUID.randomUUID().toString(),"RUN","project","session","source",null,"text/plain","result.txt",7,sha("correct"),Instant.now(),Instant.now().plusSeconds(600),"opaque","AVAILABLE");
  var wrong=new Artifact(UUID.randomUUID().toString(),"RUN","project","session","source",null,"text/plain","result.txt",5,sha("wrong"),Instant.now(),Instant.now().plusSeconds(600),"opaque2","AVAILABLE");
  var artifacts=Map.of(correct.artifactId(),correct,wrong.artifactId(),wrong);
  gate=new GoalCompletionGate(goals,(o,r)->{throw new java.io.IOException();},(o,r)->artifacts.get(r.id()),(r,d)->{throw new java.io.IOException();},Clock.systemUTC(),false);verifier.setCompletionGate(gate);
  var definition=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,List.of(new GoalCompletionGate.ArtifactRequirement("result.txt","text/plain",sha("correct"),true)),List.of(),null);
  var goal=create(definition);var a=new GoalCompletionGate.Reference(correct.artifactId(),correct.sha256());var b=new GoalCompletionGate.Reference(wrong.artifactId(),wrong.sha256());
  gate.attach(owner(),goal.id(),new GoalCompletionGate.Proof(null,List.of(a,b),List.of(b)),true);
  assertEquals("completion_delivery_pending",verifier.verify(goals.get("project",goal.id())).reason());
 }
 @Test void progressShowsOptionalFailureAndCurrentRevisionWithoutClaimingCompletion()throws Exception {
  var definition=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,List.of(),List.of(),null,
      List.of(new GoalCompletionGate.Requirement("optional","optional",false,new GoalRepository.FileCriterion("optional.txt",sha("optional")))));
  var goal=create(definition);
  var loop=new GoalLoopService(goals,verifier,(c,r,d)->fail("read only"),new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(false,null,null,null),new GoalEvents(e->{},Clock.systemUTC()));
  var view=loop.progress("project",goal.id());assertEquals("READY",view.state());assertTrue(view.verification().satisfied());
  assertEquals(sha("correct"),view.conditions().getFirst().revision());assertFalse(view.conditions().getLast().required());assertFalse(view.conditions().getLast().satisfied());
  assertEquals("READY",goals.get("project",goal.id()).status());assertEquals(0,goals.get("project",goal.id()).llmCallsUsed());
 } @Test void verificationAndRepairPhaseArePublishedThroughExistingGoalEvents()throws Exception {
  var goal=goals.create(owner(),"repair","state.json",sha("correct"),3,3);
  var phases=new ArrayList<String>();var counter=new java.util.concurrent.atomic.AtomicInteger();
  var events=new GoalEvents(e->{if(e.payload() instanceof dev.mikoto2000.rei.event.GoalLifecyclePayload p)phases.add(p.completionPhase());},Clock.systemUTC());
  var loop=new GoalLoopService(goals,verifier,(c,r,d)->{
    assertTrue(goals.reserveLlm(c));try{Files.writeString(root.resolve("state.json"),counter.incrementAndGet()==1?"wrong":"correct");}catch(Exception e){throw new RuntimeException(e);}
    d.accept(new GoalLoopService.Outcome(dev.mikoto2000.rei.core.chat.ChatExecutionResult.success("done",false)));
  },new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),events);
  assertEquals("COMPLETED",loop.run("project",goal.id()).status());
  assertEquals(List.of("RUNNING","VERIFYING","REPAIRING","VERIFYING","COMPLETED"),phases);
 } @Test void unknownRequirementEvidenceIsBlockedInsteadOfTreatedAsRepairableMismatch()throws Exception {
  Files.writeString(root.resolve("state.json"),"malformed");
  var definition=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,List.of(),List.of(),null,
      List.of(new GoalCompletionGate.Requirement("R1","required",true,new GoalRepository.FileCriterion("state.json",null,"/done","true"))));
  var goal=create(definition);assertEquals("json_file_invalid",verifier.verify(goal).reason());
 } @Test void omittedRequiredFlagMustNotSilentlyTurnAHumanConditionOptional()throws Exception {
  var definition=new GoalCompletionGate.Definition(List.of(new GoalRepository.FileCriterion("out.txt",sha("correct"))),null,List.of(),List.of(),null,
      List.of(new GoalCompletionGate.Requirement("R1","mandatory",true,new GoalRepository.FileCriterion("state.json",null,"/done","true"))));
  String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(definition).replace("\"required\":true,","");
  assertThrows(IllegalArgumentException.class,()->GoalRepository.parseCompletion(json));
 }}
