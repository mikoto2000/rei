package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.net.http.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.chat.*;
@Tag("integration")
class GoalHttpTest {
 @TempDir Path dir;
 static class Gateway implements GoalLoopService.Gateway {
  final AtomicInteger dispatched=new AtomicInteger();final AtomicBoolean live=new AtomicBoolean(true);
  public void dispatch(GoalRepository.Claim claim,String run,java.util.function.Consumer<GoalLoopService.Outcome> done){dispatched.incrementAndGet();}
  public boolean isInFlight(GoalRepository.Goal goal){return live.get();}
 }
 @Configuration(proxyBeanMethods=false)
 @Import({WebApiIntegrationTest.Config.class,GoalController.class})
 static class Config {
  @Bean GoalRepository goals(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String path){return new GoalRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+Path.of(path).resolve("goals.db")),Clock.systemUTC());}
  @Bean Gateway gateway(){return new Gateway();}
  @Bean GoalWaitService waits(){return org.mockito.Mockito.mock(GoalWaitService.class);}
  @Bean FileGoalVerifier verifier(){return new FileGoalVerifier();}
  @Bean GoalCompletionGate completionGate(GoalRepository goals){return new GoalCompletionGate(goals,(owner,ref)->{throw new java.io.IOException("fixture has no Review");},(owner,ref)->{throw new java.io.IOException("fixture has no Artifact");},(root,deadline)->{throw new java.io.IOException("fixture has no patch");},Clock.systemUTC(),false);}
  @Bean GoalLoopService loop(GoalRepository repo,Gateway gateway,FileGoalVerifier verifier){return new GoalLoopService(repo,verifier,gateway,new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),org.mockito.Mockito.mock(GoalEvents.class));}
 }
 @Test void completionDefinitionEndpointRequiresAuthenticationStrictJsonAndStoppedGoal()throws Exception{
  var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");
  try(var context=app.run("--rei.web.port=0","--rei.data-dir="+dir,"--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()){
   int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));var repo=context.getBean(GoalRepository.class);java.nio.file.Files.writeString(dir.resolve("out.txt"),"correct");String sha=context.getBean(FileGoalVerifier.class).fingerprint(dir.toRealPath(),"out.txt").sha256();var goal=repo.create(new AgentRunContext("source","session",dir,"p"),"result","out.txt",sha,2,5);String path="/projects/p/goals/"+goal.id();String json="{\"completionEvidence\":[{\"relativeFile\":\"out.txt\",\"sha256\":\""+sha+"\"}]}";
   assertEquals(401,ReadHttpTest.send(client,port,path+"/completion","PUT",json,false).statusCode());assertNull(repo.get("p",goal.id()).completion());assertEquals(200,ReadHttpTest.send(client,port,path+"/completion","PUT",json,true).statusCode());assertNotNull(repo.get("p",goal.id()).completion());
   assertEquals(400,ReadHttpTest.send(client,port,path+"/completion","PUT",json+" {}",true).statusCode());assertEquals(400,ReadHttpTest.send(client,port,path+"/completion","PUT",json.replace("\"completionEvidence\":","\"misspelled\":true,\"completionEvidence\":"),true).statusCode());repo.claim("p",goal.id());assertEquals(409,ReadHttpTest.send(client,port,path+"/completion","PUT",json,true).statusCode());assertEquals(0,context.getBean(Gateway.class).dispatched.get());
  }
 }
 @Test void optedInPredicateVerifiesThroughAuthenticatedHttpWithoutModelDispatch()throws Exception {
  var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");
  try(var context=app.run("--rei.web.port=0","--rei.data-dir="+dir,"--rei.predicates.enabled=true","--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()) {
   int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));var repo=context.getBean(GoalRepository.class);
   String predicate="{\"version\":1,\"expression\":{\"op\":\"GTE\",\"pointer\":\"/coverage\",\"value\":80}}";
   var goal=repo.create(new AgentRunContext("source","session",dir,"p"),"Coverage threshold",List.of(new GoalRepository.FileCriterion("report.json",null,null,null,predicate)),3,20);
   java.nio.file.Files.writeString(dir.resolve("report.json"),"{\"coverage\":90}");String path="/projects/p/goals/"+goal.id();
   assertEquals(401,ReadHttpTest.send(client,port,path+"/verify","POST","{}",false).statusCode());
   var response=ReadHttpTest.send(client,port,path+"/verify","POST","{}",true);assertEquals(200,response.statusCode());assertTrue(response.body().contains("predicateJson"));
   assertEquals("COMPLETED",repo.get("p",goal.id()).status());assertEquals("criteria_verified",repo.get("p",goal.id()).reason());assertEquals(0,context.getBean(Gateway.class).dispatched.get());
  }
 }
 @Test void exactRunAcknowledgementAndInFlightCheckGuardRealSavedGoalWithoutReplay() throws Exception {
  var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");
  try(var context=app.run("--rei.web.port=0","--rei.data-dir="+dir,"--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()) {
   int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));var repo=context.getBean(GoalRepository.class);var gateway=context.getBean(Gateway.class);
   var goal=repo.create(new AgentRunContext("source","session",dir,"p"),"Produce result","out.txt","a".repeat(64),3,20);String path="/projects/p/goals/"+goal.id();
   assertEquals(401,ReadHttpTest.send(client,port,path,"GET",null,false).statusCode());
   assertEquals(200,ReadHttpTest.send(client,port,"/projects/p/goals","GET",null,true).statusCode());
   assertEquals(200,ReadHttpTest.send(client,port,path+"/history","GET",null,true).statusCode());assertEquals(0,gateway.dispatched.get());
   assertEquals(401,ReadHttpTest.send(client,port,path+"/run","POST","{}",false).statusCode());
   assertEquals(200,ReadHttpTest.send(client,port,path+"/run","POST","{}",true).statusCode());assertEquals(1,gateway.dispatched.get());
   String run=repo.get("p",goal.id()).currentRunId();String body="{\"expectedRunId\":\""+run+"\",\"acknowledgeUncertainSideEffects\":true}";
   assertEquals(400,ReadHttpTest.send(client,port,path+"/reconcile","POST","{}",true).statusCode());
   assertEquals(409,ReadHttpTest.send(client,port,path+"/reconcile","POST",body,true).statusCode());
   gateway.live.set(false);
   assertEquals(409,ReadHttpTest.send(client,port,path+"/reconcile","POST","{\"expectedRunId\":\"stale\",\"acknowledgeUncertainSideEffects\":true}",true).statusCode());
   assertEquals(200,ReadHttpTest.send(client,port,path+"/reconcile","POST",body,true).statusCode());
   assertEquals("PAUSED",repo.get("p",goal.id()).status());assertEquals(1,repo.get("p",goal.id()).attempts());assertEquals(1,gateway.dispatched.get());
   assertEquals(409,ReadHttpTest.send(client,port,path+"/reconcile","POST",body,true).statusCode());
   assertEquals(400,ReadHttpTest.send(client,port,"/projects/other/goals/"+goal.id(),"GET",null,true).statusCode());
  }
 }
 @Test void waitHttpControlsRequireAuthenticationAndObservedVersionWithoutNormalGoalRun()throws Exception{
  var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");
  try(var context=app.run("--rei.web.port=0","--rei.data-dir="+dir,"--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()){
   int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
   var repo=context.getBean(GoalRepository.class);var waits=context.getBean(GoalWaitService.class);
   var goal=repo.create(new AgentRunContext("source","session",dir,"p"),"result","out.txt","a".repeat(64),2,5);
   String path="/projects/p/goals/"+goal.id()+"/wait";
   var snapshot=new GoalWaitRepository.Snapshot(context.getBean(GoalLoopService.class).progress("p",goal.id()),null,
    new dev.mikoto2000.rei.core.dependency.DependencySpec(dev.mikoto2000.rei.core.dependency.DependencySpec.Kind.FILE_EXISTS,"ready",null),null,0,List.of(),"next");
   var saved=new GoalWaitRepository.Wait("wait","p",goal.id(),null,0,"job_wait","dep","timer","WAITING","not_observed",1,null,snapshot);
   org.mockito.Mockito.when(waits.waitFor("p",goal.id(),"dep","job_wait")).thenReturn(saved);org.mockito.Mockito.when(waits.show("p",goal.id())).thenReturn(saved);
   assertEquals(401,ReadHttpTest.send(client,port,path,"POST","{\"dependencyId\":\"dep\",\"reason\":\"job_wait\"}",false).statusCode());org.mockito.Mockito.verifyNoInteractions(waits);
   var created=ReadHttpTest.send(client,port,path,"POST","{\"dependencyId\":\"dep\",\"reason\":\"job_wait\"}",true);
   assertEquals(200,created.statusCode());assertTrue(created.body().contains("\"state\":\"WAITING\""));
   assertEquals(200,ReadHttpTest.send(client,port,path,"GET",null,true).statusCode());
   assertEquals(400,ReadHttpTest.send(client,port,path+"/resume","POST","{}",true).statusCode());
   org.mockito.Mockito.when(waits.resume("p",goal.id(),1)).thenThrow(new IllegalStateException("condition unmet"));
   assertEquals(409,ReadHttpTest.send(client,port,path+"/resume","POST","{\"expectedVersion\":1}",true).statusCode());
   org.mockito.Mockito.verify(waits,org.mockito.Mockito.times(1)).resume("p",goal.id(),1);assertEquals(0,context.getBean(Gateway.class).dispatched.get());
  }
 }}
