package dev.mikoto2000.rei.reflection;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@Tag("integration")
class GoalReflectionServiceTest {
  @TempDir Path dir;
  GoalRepository goals;
  GoalReflectionRepository reflections;
  GoalReflectionService service;
  InMemoryAgentEventBus bus;
  Clock clock=Clock.systemUTC();
  @BeforeEach void setup() {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("reflection.db"));
    goals=new GoalRepository(source,clock);reflections=new GoalReflectionRepository(source,clock);bus=new InMemoryAgentEventBus();
    service=new GoalReflectionService(goals,reflections,bus,clock);service.start();
  }
  @AfterEach void close(){service.close();}
  GoalRepository.Goal goal(){return goals.create(new AgentRunContext("source","session",dir,"project"),"secret objective","out.txt","a".repeat(64),3,5);}
  @Test void recordsExpectedAndVerifiedDifferenceWithoutTreatingFailureAsAValidatedLesson() {
    var goal=goal();var claim=goals.claim("project",goal.id());String run=goals.beginAttempt(claim);
    goals.recordAttempt(claim,run,"UNVERIFIED","digest_mismatch");var stopped=goals.stop(claim,"BLOCKED","budget_exhausted");
    var events=new GoalEvents(bus,clock);events.publish(stopped);events.publish(stopped);
    var item=reflections.list("project").getFirst();assertEquals("UNVERIFIED",item.actual());assertEquals("CRITERION_NOT_SATISFIED",item.gap());
    assertEquals("REVIEW_GOAL_BUDGET",item.nextAction());assertEquals("out.txt",item.expectedFile());assertEquals(1,reflections.list("project").size());
    assertFalse(item.toString().contains("secret objective"));
    var restarted=new GoalReflectionRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("reflection.db")),clock);
    assertEquals(item.id(),restarted.list("project").getFirst().id());assertThrows(IllegalArgumentException.class,()->restarted.get("other",item.id()));
  }
  @Test void approvalAndUnknownExecutionDoNotFabricateVerificationEvidence() {
    var goal=goal();var claim=goals.claim("project",goal.id());String run=goals.beginAttempt(claim);
    goals.recordAttempt(claim,run,"WAITING_APPROVAL","execution_stopped");var stopped=goals.stop(claim,"WAITING_APPROVAL","execution_stopped");
    var item=service.collect("project",goal.id());assertEquals("NOT_CHECKED",item.actual());assertEquals("VERIFICATION_NOT_RECORDED",item.gap());
    assertEquals("REVIEW_APPROVAL",item.nextAction());assertEquals(item.id(),service.collect("project",goal.id()).id());
    new GoalEvents(bus,clock).publish(stopped);assertEquals(1,reflections.list("project").size());
  }
  @Test void completedCriterionIsRecordedAsEvidenceAndRunningGoalsCannotBeCollected() {
    var goal=goal();assertThrows(IllegalStateException.class,()->service.collect("project",goal.id()));
    goals.verifiedWithoutRun("project",goal.id());var item=service.collect("project",goal.id());
    assertEquals("VERIFIED",item.actual());assertEquals("CRITERION_SATISFIED",item.gap());assertEquals("REVIEW_VERIFIED_RESULT",item.nextAction());
  }
  @Test void lateEventUsesItsOwnAttemptInsteadOfTheLatestGoalResult() {
    var goal=goal();var first=goals.claim("project",goal.id());String run=goals.beginAttempt(first);
    goals.recordAttempt(first,run,"WAITING_APPROVAL","execution_stopped");var waiting=goals.stop(first,"WAITING_APPROVAL","execution_stopped");
    var second=goals.claim("project",goal.id());String next=goals.beginAttempt(second);goals.recordAttempt(second,next,"VERIFIED","file_digest_verified");goals.stop(second,"COMPLETED","file_digest_verified");
    new GoalEvents(bus,clock).publish(waiting);
    var item=reflections.list("project").getFirst();assertEquals(run,item.runId());assertEquals("NOT_CHECKED",item.actual());assertEquals("REVIEW_APPROVAL",item.nextAction());
  }
  @Test void shellShowsScopedFactsAndCollectDoesNotExecuteOrVerifyTheGoalAgain() {
    var goal=goal();goals.verifiedWithoutRun("project",goal.id());
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var project=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);
    org.mockito.Mockito.when(project.id()).thenReturn("project");org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);
    var command=new ReflectionCommand(reflections,service,projects);var output=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(output));
    var cli=new picocli.CommandLine(command);assertEquals(0,cli.execute("collect",goal.id()));var item=reflections.list("project").getFirst();
    assertEquals(0,cli.execute("show",item.id()));assertTrue(output.toString().contains("CRITERION_SATISFIED"));assertEquals(0,goals.get("project",goal.id()).attempts());
    org.mockito.Mockito.when(project.id()).thenReturn("other");assertEquals(2,cli.execute("show",item.id()));
  }
}
