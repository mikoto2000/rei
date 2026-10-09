package dev.mikoto2000.rei.evaluation;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
/** Paired adversarial gate evaluation; deliberately incomplete tasks, no live-model claim. */
@Tag("integration")
class GoalGateIndependentComparisonTest {
 @TempDir Path temporary;
 @Test void extraMandatoryConditionsPreventHostFalseCompletion()throws Exception{
  var payload=new LinkedHashMap<String,Object>();var evaluator=new GoalCompletionEvaluation();
  for(boolean gated:List.of(false,true)){
   var results=new ArrayList<GoalCompletionEvaluation.Result>();
   for(String scenario:List.of("missing-named-condition","missing-test-evidence","missing-artifact-handover")){
    Path root=Files.createDirectory(temporary.resolve((gated?"gate-":"legacy-")+scenario)).toRealPath();
    Files.writeString(root.resolve("out"),"correct");String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("correct".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var repo=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db")),Clock.systemUTC());
    var criterion=new GoalRepository.FileCriterion("out",digest);
    var definition=new GoalCompletionGate.Definition(List.of(criterion),
      scenario.equals("missing-test-evidence")?new GoalCompletionGate.RequiredTests("a".repeat(64),List.of("Fixture#required")):null,
      scenario.equals("missing-artifact-handover")?List.of(new GoalCompletionGate.ArtifactRequirement("out","text/plain",digest,true)):List.of(),List.of(),null,
      scenario.equals("missing-named-condition")?List.of(new GoalCompletionGate.Requirement("required","Required second output",true,new GoalRepository.FileCriterion("second",digest))):List.of());
    var goal=gated?repo.create(new AgentRunContext("human","s",root,"p"),scenario,List.of(criterion),definition,3,3):repo.create(new AgentRunContext("human","s",root,"p"),scenario,List.of(criterion),3,3);
    var verifier=new FileGoalVerifier();
    verifier.setCompletionGate(new GoalCompletionGate(repo,(o,r)->{throw new java.io.IOException("No saved test evidence");},(o,r)->{throw new java.io.IOException("No handover artifact");},(r,d)->{throw new java.io.IOException("No review");},Clock.systemUTC(),false));
    var loop=new GoalLoopService(repo,verifier,(claim,run,done)->{
     assertTrue(repo.modelBudget(claim,run).tryReserve());done.accept(new GoalLoopService.Outcome(ChatExecutionResult.success("All done",false)));
    },new ToolPermissionProperties(true,null,null,null),new GoalEvents(e->{},Clock.systemUTC()));
    long started=System.nanoTime();var end=loop.run("p",goal.id());
    // The independent oracle knows the required second output/test receipt/handover is absent.
    var checks=List.of(new GoalCompletionEvaluation.Condition("base-file",true,Files.readString(root.resolve("out")).equals("correct"),"Direct file read"),
      new GoalCompletionEvaluation.Condition("missing-required-proof",true,false,"Required second output, saved test or human handover is absent"));
    var observation=new GoalCompletionEvaluation.Observation(end.status().equals("COMPLETED"),true,Math.max(0,end.attempts()-1),0,0,Math.max(0,end.attempts()-1),0,0,
      GoalCompletionEvaluation.Measurement.known((System.nanoTime()-started)/1000000),GoalCompletionEvaluation.Measurement.known(end.llmCallsUsed()),GoalCompletionEvaluation.Measurement.unavailable("Scripted gateway; no provider usage"));
    results.add(evaluator.score(scenario,observation,checks));assertEquals(gated?"BLOCKED":"COMPLETED",end.status());
   }
   var report=evaluator.aggregate(results);assertEquals(gated?0.0:1.0,report.falseCompletionRate());assertEquals(0.0,report.verifiedCompletionRate());
   payload.put(gated?"gated":"legacy",Map.of("report",report,"results",results));
  }
  payload.put("scope","Three paired incomplete adversarial cases; host COMPLETED status, not raw model text. Not comparable to the 10-case model-self-claim baseline or live-model performance.");
  Files.writeString(Path.of("target/goal-gate-independent-comparison.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload));
 }
}