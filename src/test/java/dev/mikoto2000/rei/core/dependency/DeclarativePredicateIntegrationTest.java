package dev.mikoto2000.rei.core.dependency;

import java.nio.file.*;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.junit.jupiter.api.Assertions.*;

class DeclarativePredicateIntegrationTest {
  @TempDir Path root;
  String predicate="{\"version\":1,\"expression\":{\"op\":\"AND\",\"args\":[{\"op\":\"GTE\",\"pointer\":\"/coverage\",\"value\":80},{\"op\":\"MATCH\",\"pointer\":\"/status\",\"value\":\"passed|complete\"}]}}";
  @Test void disabledPredicateGoalCannotStartAModelRun() {
    var repository=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("disabled.db")),Clock.systemUTC());
    var goal=repository.create(new AgentRunContext("run","session",root,"project"),"structured result",List.of(new GoalRepository.FileCriterion("result.json",null,null,null,predicate)),3,10);
    var dispatched=new java.util.concurrent.atomic.AtomicInteger();
    var loop=new GoalLoopService(repository,new FileGoalVerifier(),(claim,run,done)->dispatched.incrementAndGet(),new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(event->{},Clock.systemUTC()));
    assertThrows(IllegalStateException.class,()->loop.run("project",goal.id()));assertEquals(0,dispatched.get());assertEquals("READY",repository.get("project",goal.id()).status());
  }
  @Test void fileDependencyUsesSamePredicateAndPreservesUnknownObservation()throws Exception {
    var data=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("dependencies.db"));var clock=Clock.systemUTC();var repository=new PersistentDependencyRepository(data,clock);
    var spec=new DependencySpec(DependencySpec.Kind.valueOf("FILE_JSON_PREDICATE"),"result.json",predicate);
    var entry=repository.create(new AgentRunContext("run","session",root,"project"),spec,java.time.Duration.ofHours(1),List.of());
    var restored=new PersistentDependencyRepository(data,clock).get("project",entry.id());assertEquals(spec,restored.spec());
    var verifier=new FileGoalVerifier();var probe=new DependencySourceProbe(verifier,org.mockito.Mockito.mock(dev.mikoto2000.rei.workcontext.WorkContextGit.class),org.mockito.Mockito.mock(dev.mikoto2000.rei.core.process.BackgroundProcessManager.class),org.mockito.Mockito.mock(DependencyHttpProbe.class),clock);
    Files.writeString(root.resolve("result.json"),"{\"coverage\":90,\"status\":\"passed\"}");
    assertEquals(DependencyState.BLOCKED,probe.probe(restored).state());verifier.setPredicatesEnabled(true);assertEquals(DependencyState.COMPLETED,probe.probe(restored).state());
    Files.writeString(root.resolve("result.json"),"{\"coverage\":70,\"status\":\"passed\"}");assertEquals(DependencyState.WAITING,probe.probe(restored).state());
    Files.writeString(root.resolve("result.json"),"{}");assertEquals(DependencyState.BLOCKED,probe.probe(restored).state());
  }
  @Test void goalPredicateSurvivesRestartAndIsDisabledByDefault()throws Exception {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db"));var repository=new GoalRepository(source,Clock.systemUTC());
    var owner=new AgentRunContext("run","session",root,"project");
    var goal=repository.create(owner,"verified structured result",List.of(new GoalRepository.FileCriterion("result.json",null,null,null,predicate)),3,10);
    var saved=new GoalRepository(source,Clock.systemUTC()).get("project",goal.id());assertNotNull(saved.criteria().getFirst().predicateJson());
    Files.writeString(root.resolve("result.json"),"{\"coverage\":90,\"status\":\"passed\"}");var verifier=new FileGoalVerifier();
    assertEquals("predicate_disabled",verifier.verify(saved).reason());verifier.setPredicatesEnabled(true);
    assertTrue(verifier.verify(saved).satisfied());
    Files.writeString(root.resolve("result.json"),"{\"coverage\":70,\"status\":\"passed\"}");assertEquals("predicate_mismatch",verifier.verify(saved).reason());
    Files.writeString(root.resolve("result.json"),"{\"status\":\"passed\"}");assertEquals("predicate_unknown",verifier.verify(saved).reason());
    assertThrows(IllegalArgumentException.class,()->repository.create(owner,"bad",List.of(new GoalRepository.FileCriterion("result.json","a".repeat(64),null,null,predicate)),3,10));
  }
  @Test void httpPredicateReusesBoundedTransportWithoutExposingResponseBody()throws Exception {
    var body=new AtomicReference<>("{\"coverage\":90,\"status\":\"passed\",\"private\":\"not in receipt\"}");
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/state",exchange->{byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    try(var probe=new JavaHttpDependencyProbe()) {
      String url="http://127.0.0.1:"+server.getAddress().getPort()+"/state",expected="{\"status\":200,\"predicate\":"+predicate+"}";
      assertEquals("predicate_disabled",probe.probeJson("id",url,expected).detail());probe.setPredicatesEnabled(true);
      assertEquals(DependencyState.COMPLETED,probe.probeJson("id",url,expected).state());
      body.set("{\"status\":\"passed\"}");var unknown=probe.probeJson("id",url,expected);assertEquals(DependencyState.BLOCKED,unknown.state());assertEquals("predicate_unknown",unknown.detail());
      body.set("{\"coverage\":70,\"status\":\"passed\"}");assertEquals(DependencyState.WAITING,probe.probeJson("id",url,expected).state());
    }finally{server.stop(0);}
  }
}
