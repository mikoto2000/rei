package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.nio.file.Path;
import java.net.http.HttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.SpringApplication;
import dev.mikoto2000.rei.temporal.*;
import dev.mikoto2000.rei.core.chat.*;

@Tag("integration")
class ScheduleHttpTest {
 @TempDir Path dir;
 @Configuration(proxyBeanMethods=false)
 @Import({WebApiIntegrationTest.Config.class,ScheduleController.class})
 static class Config {
  @Bean PersistentAgentScheduler schedules(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String path) {
   return new PersistentAgentScheduler(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+Path.of(path).resolve("schedules.db")),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
  }
  @Bean AgentScheduleDispatcher dispatcher(){return mock(AgentScheduleDispatcher.class);}
 }
 @Test void authenticatedExplicitControlsPreserveOwnershipAndNeverReplayUncertainExecution() throws Exception {
  var app=new SpringApplication(Config.class);WebApplication.configure(app,"integration-key");
  try(var context=app.run("--rei.web.port=0","--rei.data-dir="+dir,"--logging.config=classpath:web-test-logback.xml");var client=HttpClient.newHttpClient()) {
   int port=Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
   var repo=context.getBean(PersistentAgentScheduler.class);var dispatcher=context.getBean(AgentScheduleDispatcher.class);
   String id,cancelId;
   try(var scope=AgentRunScope.open(new AgentRunContext("source","session",dir,"p"))) {
    id=repo.scheduleInterval(Duration.ofMinutes(1),2,"Inspect saved result","session").id();
    cancelId=repo.scheduleAfter(Duration.ZERO,"Do not execute","session").id();
   }
   String path="/projects/p/schedules/"+id;
   assertEquals(401,ReadHttpTest.send(client,port,path,"GET",null,false).statusCode());
   assertEquals(200,ReadHttpTest.send(client,port,"/projects/p/schedules","GET",null,true).statusCode());
   var shown=ReadHttpTest.send(client,port,path,"GET",null,true);
   assertEquals(200,shown.statusCode());assertTrue(shown.body().contains("intervalMillis"));assertTrue(shown.body().contains("60000"));
   assertEquals(200,ReadHttpTest.send(client,port,path+"/history","GET",null,true).statusCode());
   assertEquals("PENDING",repo.get("p",id).status());verify(dispatcher,never()).reconcile(anyString(),anyString(),anyString());
   assertEquals(400,ReadHttpTest.send(client,port,"/projects/other/schedules/"+id,"GET",null,true).statusCode());
   assertEquals(401,ReadHttpTest.send(client,port,path+"/activate","POST","{}",false).statusCode());
   assertEquals(200,ReadHttpTest.send(client,port,path+"/activate","POST","{}",true).statusCode());
   assertEquals("SCHEDULED",repo.get("p",id).status());verify(dispatcher,never()).reconcile(anyString(),anyString(),anyString());
   assertEquals(409,ReadHttpTest.send(client,port,path+"/activate","POST","{}",true).statusCode());
   String cancelPath="/projects/p/schedules/"+cancelId+"/cancel";
   assertEquals(400,ReadHttpTest.send(client,port,"/projects/other/schedules/"+cancelId+"/cancel","POST","{}",true).statusCode());
   assertEquals(200,ReadHttpTest.send(client,port,cancelPath,"POST","{}",true).statusCode());
   assertEquals(409,ReadHttpTest.send(client,port,cancelPath,"POST","{}",true).statusCode());
   // An actual persisted one-shot claim stands in for a run interrupted by restart.
   String uncertain;
   try(var scope=AgentRunScope.open(new AgentRunContext("source","session",dir,"p"))) {
    uncertain=repo.scheduleAfter(Duration.ZERO,"Inspect uncertain effects","session").id();
   }
   repo.activate("p",uncertain);var claim=repo.claimDue().orElseThrow();assertEquals(uncertain,claim.task().id());
   String reconcilePath="/projects/p/schedules/"+uncertain+"/reconcile";
   String body="{\"expectedRunId\":\""+claim.runId()+"\",\"acknowledgeUncertainSideEffects\":true}";
   assertEquals(401,ReadHttpTest.send(client,port,reconcilePath,"POST",body,false).statusCode());
   assertEquals(400,ReadHttpTest.send(client,port,reconcilePath,"POST","{}",true).statusCode());
   verify(dispatcher,never()).reconcile(anyString(),anyString(),anyString());
   when(dispatcher.reconcile("p",uncertain,claim.runId())).thenThrow(new IllegalStateException("queued or executing"));
   assertEquals(409,ReadHttpTest.send(client,port,reconcilePath,"POST",body,true).statusCode());
   assertEquals("RUNNING",repo.get("p",uncertain).status());
   doAnswer(invocation->repo.reconcile("p",uncertain,claim.runId())).when(dispatcher).reconcile("p",uncertain,claim.runId());
   assertEquals(200,ReadHttpTest.send(client,port,reconcilePath,"POST",body,true).statusCode());
   assertEquals("FAILED",repo.get("p",uncertain).status());assertEquals(claim.runId(),repo.get("p",uncertain).runId());
   assertEquals(409,ReadHttpTest.send(client,port,reconcilePath,"POST",body,true).statusCode());
   assertTrue(repo.claimDue().isEmpty());
  }
 }
}

