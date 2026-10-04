package dev.mikoto2000.rei.reflection;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.core.chat.*;
@Tag("integration")
class RunReflectionServiceTest {
  @TempDir Path dir;
  Clock clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC);
  RunReflectionRepository repo;RunReflectionService service;InMemoryAgentEventBus bus;
  AgentEventFactory events;AgentRunContext owner=new AgentRunContext("run","session",Path.of("."),"project");
  @BeforeEach void setup(){repo=new RunReflectionRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("reflection.db")),clock);bus=new InMemoryAgentEventBus();events=new AgentEventFactory(clock);service=new RunReflectionService(repo,bus);service.start();}
  @AfterEach void close(){service.close();}
  @Test void completedRunRecordsActualToolFactsAndDoesNotInventTaskVerification() {
    var tool=events.toolCompleted("call","readFile",1L,"token=private",null,10L,null,null).withOwnership(owner);
    bus.publish(tool);bus.publish(tool);bus.publish(events.runCompleted("run",1).withOwnership(owner));
    var item=repo.list("project").getFirst();assertEquals("RUN",item.sourceKind());assertEquals("COMPLETED",item.status());
    assertEquals(1,item.actions().size());assertEquals("readFile",item.actions().getFirst().tool());
    assertEquals("TASK_CRITERIA_NOT_DECLARED",item.gap());assertFalse(item.toString().contains("private"));
    assertEquals("INSPECT_RESULT_AND_VERIFY_CRITERIA",item.nextAction());
  }
  @Test void failuresAreStructuredRedactedDurableAndDeduplicated() {
    bus.publish(events.toolFailed("call","writeFile",new ErrorInformation("PermissionRequired","token=private",null)).withOwnership(owner));
    var terminal=events.runFailed("run",new ErrorInformation("ExecutionFailure","token=private",null)).withOwnership(owner);
    bus.publish(terminal);bus.publish(terminal);var item=repo.list("project").getFirst();
    assertEquals("ExecutionFailure",item.failureReason());assertEquals("REVIEW_APPROVAL_BEFORE_RETRY",item.nextAction());
    assertFalse(item.knowledgeCandidates().isEmpty());assertFalse(item.toString().contains("private"));
    var restored=new RunReflectionRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("reflection.db")),clock);
    assertEquals(item,restored.get("project",item.id()));assertEquals(1,restored.list("project").size());
    assertThrows(IllegalArgumentException.class,()->restored.get("other",item.id()));
  }
  @Test void taskCompletionAndFailureAreIndependentFromParentRun() {
    bus.publish(events.taskCreated("task",null,"Expected task","OPEN").withOwnership(owner));
    var done=events.taskCompleted("task",1L).withOwnership(owner);bus.publish(done);bus.publish(done);
    bus.publish(events.taskFailed("other-task",new ErrorInformation("TaskFailure","private",null)).withOwnership(owner));
    assertEquals(2,repo.list("project").size());
    var item=repo.list("project").stream().filter(i->i.sourceId().equals("task")).findFirst().orElseThrow();
    assertEquals("TASK",item.sourceKind());assertEquals("Expected task",item.expected());assertEquals("TASK_COMPLETION_REPORTED",item.gap());
  }
  @Test void unownedOrMismatchedRunEventsCannotCreateReflections() {
    bus.publish(events.runCompleted("run",1));
    var event=events.runCompleted("wrong",1);
    bus.publish(new AgentEvent(event.id(),0,event.timestamp(),event.type(),1,"session",null,"run",null,null,event.payload(),"project"));
    assertTrue(repo.list("project").isEmpty());
  }
  @Test void shellExposesRunFactsWithinCurrentProject() {
    bus.publish(events.runCompleted("run",1).withOwnership(owner));var item=repo.list("project").getFirst();
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var context=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);org.mockito.Mockito.when(context.id()).thenReturn("project");
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(context);
    var command=new ReflectionCommand(null,null,projects);command.setRunReflections(repo);
    var output=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(output));var cli=new picocli.CommandLine(command);
    assertEquals(0,cli.execute("runs"));assertEquals(0,cli.execute("run",item.id()));assertTrue(output.toString().contains("TASK_CRITERIA_NOT_DECLARED"));
    org.mockito.Mockito.when(context.id()).thenReturn("other");
    assertEquals(2,cli.execute("run",item.id()));
  }
  @Test void actionFactsSurviveRestartAndOverflowIsExplicit() {
    for(int i=0;i<258;i++)bus.publish(events.toolCompleted("call-"+i,"readFile",1,"",null,null,null,null).withOwnership(owner));
    service.close();var restored=new RunReflectionRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("reflection.db")),clock);
    service=new RunReflectionService(restored,bus);service.start();bus.publish(events.runCompleted("run",1).withOwnership(owner));
    var item=restored.list("project").getFirst();assertEquals(256,item.actions().size());assertTrue(item.actionsTruncated());
    assertTrue(item.knowledgeCandidates().stream().anyMatch(k->k.contains("256")));
  }

  @Test void missedTerminalCanBeCollectedFromPersistedOwnedEventsWithoutExecution() {
    String project=UUID.randomUUID().toString();var owned=new AgentRunContext("saved-run","saved-session",dir,project);
    var history=new ProjectAgentEventStore(dir.resolve("history"));
    history.append(events.toolCompleted("saved-call","readFile",1,"private").withOwnership(owned));
    history.append(events.runCompleted("saved-run",1).withOwnership(owned));
    var collector=new RunReflectionService(repo,bus,history);
    var item=collector.collect(project,"saved-session","saved-run");assertEquals(1,item.actions().size());
    assertEquals(item.id(),collector.collect(project,"saved-session","saved-run").id());
    assertThrows(IllegalArgumentException.class,()->collector.collect(project,"other-session","saved-run"));
    assertThrows(IllegalArgumentException.class,()->collector.collect(project,"saved-session","unknown"));
    assertFalse(item.toString().contains("private"));
  }

}
