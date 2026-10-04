package dev.mikoto2000.rei.attention;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@Tag("integration")
class AttentionServiceTest {
  @TempDir Path dir;
  Clock clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC);
  AttentionRepository repository;
  InMemoryAgentEventBus bus;
  AttentionService service;
  AtomicLong time;
  AgentRunContext owner(String run){return new AgentRunContext(run,"session",dir,"project");}
  AgentEventFactory factory(){return new AgentEventFactory(clock);}
  @BeforeEach void setup() {
    repository=new AttentionRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("attention.db")),clock);
    bus=new InMemoryAgentEventBus();time=new AtomicLong();service=new AttentionService(repository,bus,bus,clock,time::get);service.start();
  }
  @AfterEach void close(){service.close();}
  AgentEvent waiting(String run) {
    return factory().executionProgress(AgentEventType.STAGNATION_UPDATED,run,new ExecutionProgressPayload(null,0,3,0,1,"waiting_for_dependency")).withOwnership(owner(run));
  }
  @Test void permissionEscalationIsPersistentDeduplicatedAndAckDoesNotGrantPermission() {
    var received=new java.util.ArrayList<AgentEvent>();bus.subscribe(received::add);
    var event=factory().toolFailed("call","writeMultiFile",new ErrorInformation("PermissionRequired","secret arguments","REQUIRE_APPROVAL")).withOwnership(owner("run"));
    bus.publish(event);bus.publish(event);
    var item=repository.list("project").getFirst();assertEquals("APPROVAL_REQUIRED",item.kind());assertFalse(item.message().contains("secret"));
    assertEquals(1,received.stream().filter(e->e.type().value().equals("attention.required")).count());
    var reopened=new AttentionRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("attention.db")),clock);
    assertEquals(item.id(),reopened.list("project").getFirst().id());
    assertThrows(IllegalArgumentException.class,()->reopened.acknowledge("other",item.id()));
    reopened.acknowledge("project",item.id());bus.publish(event);assertTrue(reopened.list("project").isEmpty());
  }
  @Test void longWaitRequiresVerifiedWaitingAndProgressResetsItsDeadline() {
    bus.publish(waiting("run"));time.set(Duration.ofSeconds(119).toNanos());bus.publish(waiting("run"));assertTrue(repository.list("project").isEmpty());
    bus.publish(factory().executionProgress(AgentEventType.STAGNATION_RECOVERED,"run",new ExecutionProgressPayload(null,0,3,0,1,"progress")).withOwnership(owner("run")));
    bus.publish(waiting("run"));time.addAndGet(Duration.ofSeconds(120).toNanos());bus.publish(waiting("run"));bus.publish(waiting("run"));
    assertEquals(1,repository.list("project").size());assertEquals("LONG_WAIT",repository.list("project").getFirst().kind());
  }
  @Test void terminalAndUnownedEventsDoNotCreateStaleWaitsOrGlobalItems() {
    bus.publish(waiting("run"));bus.publish(factory().runCompleted("run",1).withOwnership(owner("run")));
    time.set(Duration.ofHours(1).toNanos());bus.publish(waiting("run"));assertTrue(repository.list("project").isEmpty());
    bus.publish(factory().toolFailed("call","x",new ErrorInformation("PermissionRequired","x",null)));assertTrue(repository.list("project").isEmpty());
    bus.publish(factory().executionProgress(AgentEventType.STAGNATION_STOPPED,"stopped",new ExecutionProgressPayload(null,3,3,1,1,"stopped")).withOwnership(owner("stopped")));
    assertEquals("STAGNATION_STOPPED",repository.list("project").getFirst().kind());
  }
  @Test void actualPermissionGuardAndAcknowledgementNeverGrantTheRequestedAction() {
    var approvals=new dev.mikoto2000.rei.core.policy.ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("attention.db")),clock);
    var policy=new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null));
    var guard=new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(policy,factory(),bus);guard.setApprovals(approvals);
    assertThrows(dev.mikoto2000.rei.core.policy.ToolPermissionException.class,()->guard.check("writeMultiFile","{}",owner("guard-run")));
    var attention=repository.list("project").getFirst();repository.acknowledge("project",attention.id());
    assertThrows(dev.mikoto2000.rei.core.policy.ToolPermissionException.class,()->guard.check("writeMultiFile","{}",owner("guard-run")));
    assertEquals("PENDING",approvals.list("project").getFirst().status());assertTrue(repository.list("project").isEmpty());
  }
  @Test void stoppedGoalProducesOneOwnedAttentionItem() {
    var event=new AgentEvent("goal-event",0,Instant.EPOCH,AgentEventType.GOAL_UPDATED,1,"session",null,"run",null,null,
        new GoalLifecyclePayload("goal","BLOCKED",3,3,20,20,"budget_exhausted"),"project");
    bus.publish(event);bus.publish(event);assertEquals(1,repository.list("project").size());
    assertEquals("GOAL_STOPPED",repository.list("project").getFirst().kind());
  }
  @Test void shellAndWebControlsAcknowledgeOnlyInsideTheOwningProject() {
    bus.publish(factory().toolFailed("call","tool",new ErrorInformation("PermissionDenied","unsafe message",null)).withOwnership(owner("denied")));
    var item=repository.list("project").getFirst();
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var project=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);
    org.mockito.Mockito.when(project.id()).thenReturn("project");org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);
    var command=new AttentionCommand(repository,projects);var output=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(output));
    var cli=new picocli.CommandLine(command);assertEquals(0,cli.execute("show",item.id()));assertTrue(output.toString().contains("POLICY_DENIED"));
    var api=new dev.mikoto2000.rei.web.AttentionController(repository);
    assertThrows(IllegalArgumentException.class,()->api.acknowledge("other",item.id()));assertEquals(1,api.list("project").size());
    assertEquals(0,cli.execute("ack",item.id()));assertEquals("ACKNOWLEDGED",api.show("project",item.id()).status());assertTrue(api.list("project").isEmpty());
  }
}
