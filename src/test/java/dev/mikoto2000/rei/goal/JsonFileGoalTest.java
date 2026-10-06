package dev.mikoto2000.rei.goal;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.junit.jupiter.api.Assertions.*;

class JsonFileGoalTest {
  @TempDir Path root;
  GoalRepository repository() {return new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());}
  AgentRunContext owner(){return new AgentRunContext("run","session",root,"project");}
  @Test void scalarCriteriaSurviveRestartAndRequireEveryTypedField() throws Exception {
    var criteria=List.of(new GoalRepository.FileCriterion("result.json",null,"/ready","true"),
        new GoalRepository.FileCriterion("result.json",null,"/count","1.00"));
    var goal=repository().create(owner(),"structured result",criteria,3,10);
    Files.writeString(root.resolve("result.json"),"{\"ready\":true,\"count\":1}");
    var saved=repository().get("project",goal.id());assertEquals(2,saved.criteria().size());
    assertEquals("/count",saved.criteria().getLast().jsonPointer());
    assertTrue(new FileGoalVerifier().verify(saved).satisfied());
    Files.writeString(root.resolve("result.json"),"{\"ready\":\"true\",\"count\":1}");
    assertEquals("json_value_mismatch",new FileGoalVerifier().verify(saved).reason());
  }
  @Test void completedJsonGoalsReflectVerifiedConditionAndRetryMismatchWithinBudget() throws Exception {
    var repository=repository();var goal=repository.create(owner(),"ready",List.of(new GoalRepository.FileCriterion("result.json",null,"/ready","true")),3,5);
    var loop=new GoalLoopService(repository,new FileGoalVerifier(),(claim,run,done)-> {
      assertTrue(repository.reserveLlm(claim));
      try{Files.writeString(root.resolve("result.json"),claim.goal().attempts()==0&&repository.get("project",goal.id()).attempts()==1?
          "{\"ready\":false}":"{\"ready\":true}");}catch(Exception error){throw new RuntimeException(error);}
      done.accept(new GoalLoopService.Outcome(dev.mikoto2000.rei.core.chat.ChatExecutionResult.success("done",false)));
    },new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(e->{},Clock.systemUTC()));
    var completed=loop.run("project",goal.id());assertEquals("COMPLETED",completed.status());assertEquals(2,completed.attempts());
    assertEquals("criteria_verified",completed.reason());
    var reflections=new dev.mikoto2000.rei.reflection.GoalReflectionRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());
    var service=new dev.mikoto2000.rei.reflection.GoalReflectionService(repository,reflections,
        org.mockito.Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class),Clock.systemUTC());
    var reflection=service.collect("project",goal.id());assertEquals("VERIFIED",reflection.actual());
    assertTrue(reflection.expectedSha256().contains("/ready"));assertTrue(reflection.expectedSha256().contains("expectedJson"));
  }
  @Test void mixedDigestsEscapedPointersNullAndPreciseNumbersRemainTyped() throws Exception {
    Files.writeString(root.resolve("A.txt"),"a");
    String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{'a'}));
    var criteria=List.of(new GoalRepository.FileCriterion("A.txt",hash),
        new GoalRepository.FileCriterion("result.json",null,"/a~1b/~0v/0","null"),
        new GoalRepository.FileCriterion("result.json",null,"/count","1.00000000000000001"));
    var goal=repository().create(owner(),"mixed",criteria,3,10);var verifier=new FileGoalVerifier();
    Files.writeString(root.resolve("result.json"),"{\"a/b\":{\"~v\":[null]},\"count\":1.00000000000000001}");
    assertTrue(verifier.verify(goal).satisfied());assertTrue(verifier.verify(root,goal.criteria().getLast()).satisfied());
    Files.writeString(root.resolve("result.json"),"{\"a/b\":{\"~v\":[null]},\"count\":1}");assertFalse(verifier.verify(goal).satisfied());
    Files.writeString(root.resolve("result.json"),"{\"a/b\":{\"~v\":[]},\"count\":1.00000000000000001}");assertFalse(verifier.verify(goal).satisfied());
    Files.writeString(root.resolve("A.txt"),"b");assertEquals("digest_mismatch",verifier.verify(goal).reason());
  }
  @Test void invalidAndDuplicateConditionsLeaveNoPersistedGoal() throws Exception {
    var repository=repository();var valid=new GoalRepository.FileCriterion("result.json",null,"/ready","true");
    var invalid=List.of(new GoalRepository.FileCriterion("../escape",null,"/ready","true"),
        new GoalRepository.FileCriterion("result.json","a".repeat(64),"/ready","true"),
        new GoalRepository.FileCriterion("result.json",null,"ready","true"),
        new GoalRepository.FileCriterion("result.json",null,"/bad~2","true"),
        new GoalRepository.FileCriterion("result.json",null,"/x",null),
        new GoalRepository.FileCriterion("result.json",null,null,"true"),
        new GoalRepository.FileCriterion("result.json",null,"/x","{}"),
        new GoalRepository.FileCriterion("result.json",null,"/x","[]"),
        new GoalRepository.FileCriterion("result.json",null,"/x","true false"),
        new GoalRepository.FileCriterion("result.json",null,"/x","1".repeat(65)),
        new GoalRepository.FileCriterion("result.json",null,"/x","\""+"a".repeat(1024)+"\""),
        new GoalRepository.FileCriterion("result.json",null,"/x".repeat(17),"true"));
    for(var item:invalid)assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x",List.of(item),3,10));
    assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x",List.of(valid,new GoalRepository.FileCriterion("./result.json",null,"/ready","false")),3,10));
    assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x",Collections.nCopies(17,valid),3,10));
    assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x","result.json",null,3,10));
    assertTrue(repository.list("project").isEmpty());
  }
  @Test void strictBoundedArtifactParsingNeverAcceptsAmbiguousOrInvalidJson() throws Exception {
    var goal=repository().create(owner(),"ready",List.of(new GoalRepository.FileCriterion("result.json",null,"/ready","true")),3,10);
    var verifier=new FileGoalVerifier();
    for(String body:List.of("{\"ready\":false,\"ready\":true}","{\"ready\":true} {}","{private-secret",
        "{\"ready\":true,\"deep\":"+"[".repeat(33)+"0"+"]".repeat(33)+"}")) {
      Files.writeString(root.resolve("result.json"),body);var result=verifier.verify(goal);
      assertEquals("json_file_invalid",result.reason());assertFalse(result.satisfied());assertFalse(result.reason().contains("private-secret"));
    }
    Files.write(root.resolve("result.json"),new byte[]{(byte)0xff});assertEquals("json_file_invalid",verifier.verify(goal).reason());
    Files.writeString(root.resolve("result.json"),"{\"ready\":true}"+" ".repeat(65537));assertEquals("json_file_too_large",verifier.verify(goal).reason());
    String prefix="{\"ready\":true}";Files.writeString(root.resolve("result.json"),prefix+" ".repeat(65536-prefix.length()));
    assertTrue(verifier.verify(goal).satisfied());
  }
  @Test void projectPathDirectoryAndCancellationGatesStillApply() throws Exception {
    var criterion=new GoalRepository.FileCriterion("result.json",null,"/ready","true");
    var verifier=new FileGoalVerifier();assertEquals("file_missing_or_not_regular",verifier.verify(root,criterion).reason());
    Files.createDirectory(root.resolve("result.json"));assertEquals("file_missing_or_not_regular",verifier.verify(root,criterion).reason());
    Files.delete(root.resolve("result.json"));Files.writeString(root.resolve("result.json"),"{\"ready\":true}");
    assertEquals("project_path_changed",verifier.verify(root.resolve("."),criterion).reason());
    Thread.currentThread().interrupt();
    try{assertEquals("verification_cancelled",verifier.verify(root,criterion).reason());}finally{Thread.interrupted();}
  }
  @Test void alreadySatisfiedJsonRequiresNoRunOrModelAndKeepsReflectionEvidence() throws Exception {
    var repository=repository();var goal=repository.create(owner(),"ready",List.of(new GoalRepository.FileCriterion("result.json",null,"/ready","true")),3,10);
    Files.writeString(root.resolve("result.json"),"{\"ready\":true}");
    var called=new java.util.concurrent.atomic.AtomicInteger();
    var loop=new GoalLoopService(repository,new FileGoalVerifier(),(claim,run,done)->called.incrementAndGet(),
        new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(e->{},Clock.systemUTC()));
    var completed=loop.run("project",goal.id());assertEquals("COMPLETED",completed.status());assertEquals("criteria_verified",completed.reason());
    assertEquals(0,completed.attempts());assertEquals(0,completed.llmCallsUsed());assertEquals(0,called.get());
    assertThrows(IllegalArgumentException.class,()->repository.get("other-project",goal.id()));
  }
  @Test void actualCliCreatesTypedConditionsAndRejectsDuplicateFieldsBeforeSaving() throws Exception {
    var repository=repository();var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    String projectId="00000000-0000-0000-0000-000000000001";
    var projectContext=new dev.mikoto2000.rei.core.project.ProjectContext(projectId,"test",root);
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(projectContext);
    org.mockito.Mockito.when(projects.currentSessionId()).thenReturn("session");
    var loop=new GoalLoopService(repository,new FileGoalVerifier(),(claim,run,done)->fail("create must not dispatch"),
        new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null),new GoalEvents(e->{},Clock.systemUTC()));
    var cli=new picocli.CommandLine(new GoalCommand(repository,loop,projects));cli.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(0,cli.execute("create","ready","--criteria-json","[{\"relativeFile\":\"result.json\",\"jsonPointer\":\"/ready\",\"expectedJson\":\"true\"}]"));
    assertEquals("/ready",repository.list(projectId).getFirst().criteria().getFirst().jsonPointer());
    assertEquals(2,cli.execute("create","bad","--criteria-json","[{\"relativeFile\":\"result.json\",\"jsonPointer\":\"/ready\",\"expectedJson\":\"false\",\"expectedJson\":\"true\"}]"));
    assertEquals(1,repository.list(projectId).size());
  }
  @Test void legacyCriteriaSchemaUpgradesWithoutLosingDigestOrBudget() throws Exception {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db"));
    var db=org.springframework.jdbc.core.simple.JdbcClient.create(source);
    db.sql("CREATE TABLE agent_goal_criteria(goal TEXT NOT NULL,ordinal INTEGER NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(goal,ordinal))").update();
    var repository=repository();var old=repository.create(owner(),"legacy","A.txt","a".repeat(64),3,7);
    var fresh=repository.create(owner(),"new",List.of(new GoalRepository.FileCriterion("result.json",null,"/ready","true")),3,10);
    assertEquals("a".repeat(64),repository().get("project",old.id()).criteria().getFirst().sha256());
    assertEquals(7,repository().get("project",old.id()).maxLlmCalls());
    assertEquals("/ready",repository().get("project",fresh.id()).criteria().getFirst().jsonPointer());
  }
}
