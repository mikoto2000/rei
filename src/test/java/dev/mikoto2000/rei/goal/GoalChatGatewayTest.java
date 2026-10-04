package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

@Tag("integration")
class GoalChatGatewayTest {
  @TempDir Path dir;
  String project="00000000-0000-0000-0000-000000000001";
  GoalRepository goals;
  ProjectService projects;
  ChatExecutionService chat;
  ArrayDeque<Runnable> jobs;
  GoalLoopService loop;
  List<AgentEvent> events;
  GoalRepository.Goal goal;
  GoalChatGateway gateway;
  ConversationInputRouter router;
  AgentEventBus bus;
  dev.mikoto2000.rei.application.run.RunService tracking;
  dev.mikoto2000.rei.application.run.RunRegistry registry;
  @AfterEach void closeTracking(){if(tracking!=null)tracking.close();}
  void trackRuns() {
    bus=new InMemoryAgentEventBus();registry=new dev.mikoto2000.rei.application.run.RunRegistry(Clock.systemUTC());
    tracking=new dev.mikoto2000.rei.application.run.RunService(registry,bus,new AgentEventFactory(Clock.systemUTC()),new dev.mikoto2000.rei.core.service.CommandCancellationService(),router::cancelQueued);
    gateway.configureRunTracking(registry,tracking);
  }
  @BeforeEach void setup() throws Exception {
    goals=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goals.db")),Clock.systemUTC());
    projects=mock(ProjectService.class);var context=new ProjectContext(project,"test",dir);
    when(projects.registeredProjects()).thenReturn(List.of(context));when(projects.currentContext()).thenReturn(context);when(projects.currentSessionId()).thenReturn("session");
    var sessions=mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    when(sessions.findById("session")).thenReturn(Optional.of(new dev.mikoto2000.rei.application.session.SessionMetadata("session",project,"test",Instant.EPOCH,Instant.EPOCH)));
    chat=mock(ChatExecutionService.class);jobs=new ArrayDeque<>();events=new ArrayList<>();
    router=new ConversationInputRouter(jobs::add,(owner,prompt,queue)->{});
    var verifier=new FileGoalVerifier();gateway=new GoalChatGateway(goals,verifier,projects,sessions,router,chat,
        new dev.mikoto2000.rei.core.service.CommandCancellationService(),new AgentEventFactory(Clock.systemUTC()),event->{events.add(event);if(bus!=null)bus.publish(event);});
    loop=new GoalLoopService(goals,verifier,gateway,new ToolPermissionProperties(true,null,null,null),new GoalEvents(events::add,Clock.systemUTC()));
    String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("correct".getBytes()));
    goal=loop.create(new AgentRunContext("source","session",dir,project),"Write correct artifact","out.txt",digest,3,4);
  }
  @Test void actualGatewayPassesCapturedOwnerAndPersistentReservationThroughFifo() throws Exception {
    when(chat.execute(any(),anyString(),any(),any())).thenAnswer(invocation->{
      var owner=invocation.getArgument(0,AgentRunContext.class);assertEquals(project,owner.projectId());assertEquals("session",owner.conversationId());assertEquals(dir,owner.projectRoot());
      var reservation=invocation.getArgument(3,OutputLimitRunBudget.LlmCallReservation.class);
      var budget=new OutputLimitRunBudget(1,2,reservation);assertTrue(budget.tryConsumeLlmCall());
      Files.writeString(dir.resolve("out.txt"),"correct");return ChatExecutionResult.success("Done",false);
    });
    assertEquals("RUNNING",loop.run(project,goal.id()).status());verifyNoInteractions(chat);assertEquals(1,jobs.size());
    jobs.remove().run();assertEquals("COMPLETED",goals.get(project,goal.id()).status());assertEquals(1,goals.get(project,goal.id()).llmCallsUsed());
    assertTrue(events.stream().anyMatch(e->e.payload() instanceof GoalLifecyclePayload p&&p.status().equals("COMPLETED")&&project.equals(e.projectId())));
  }
  @Test void trackedGoalRetainsSessionAndReachesTerminalRunWithoutDuplicateExecution() throws Exception {
    trackRuns();
    when(chat.execute(any(),anyString(),any(),any())).thenAnswer(invocation->{var owner=invocation.getArgument(0,AgentRunContext.class);assertEquals(dev.mikoto2000.rei.application.run.RunStatus.RUNNING,tracking.get(owner.runId()).status());Files.writeString(dir.resolve("out.txt"),"correct");return ChatExecutionResult.success("Done",false);});
    var running=loop.run(project,goal.id());var queued=tracking.get(running.currentRunId());assertEquals(dev.mikoto2000.rei.application.run.RunStatus.QUEUED,queued.status());assertEquals("session",queued.context().conversationId());assertEquals(project,queued.context().projectId());
    jobs.remove().run();assertEquals(dev.mikoto2000.rei.application.run.RunStatus.COMPLETED,tracking.get(running.currentRunId()).status());assertEquals("COMPLETED",goals.get(project,goal.id()).status());verify(chat,times(1)).execute(any(),anyString(),any(),any());
  }
  @Test void normalRunCancellationPausesQueuedGoalWithoutExecutingChat() {
    trackRuns();router.submitOperation(new AgentRunContext("block","other",dir,project),()->{},Runnable::run);
    var running=loop.run(project,goal.id());assertTrue(tracking.cancel(running.currentRunId()).accepted());assertFalse(tracking.cancel(running.currentRunId()).accepted());
    assertEquals("PAUSED",goals.get(project,goal.id()).status());assertEquals("CANCELLED",goals.attempts(project,goal.id()).getFirst().status());jobs.remove().run();verifyNoInteractions(chat);assertTrue(jobs.isEmpty());
  }
  @Test void queuedIndependentVerificationPublishesTerminalWithoutCallingChat() throws Exception {
    trackRuns();var running=loop.run(project,goal.id());Files.writeString(dir.resolve("out.txt"),"correct");jobs.remove().run();
    assertEquals("COMPLETED",goals.get(project,goal.id()).status());assertEquals(dev.mikoto2000.rei.application.run.RunStatus.COMPLETED,tracking.get(running.currentRunId()).status());verifyNoInteractions(chat);
  }
  @Test void restoredGoalWithNoLiveRunCanStillBeCancelled() {
    trackRuns();var claim=goals.claim(project,goal.id());goals.beginAttempt(claim);
    assertEquals("CANCELLED",loop.cancel(project,goal.id()).status());verifyNoInteractions(chat);
  }
  @Test void queuedGoalIsCancelledWithoutExecutingOrLeavingAnAttemptRunning() {
    loop.run(project,goal.id());loop.cancel(project,goal.id());jobs.remove().run();verifyNoInteractions(chat);
    assertEquals("CANCELLED",goals.get(project,goal.id()).status());assertEquals("CANCELLED",goals.attempts(project,goal.id()).getFirst().status());
  }
  @Test void relocatedProjectIsRecheckedBeforeQueuedExecution() {
    loop.run(project,goal.id());when(projects.registeredProjects()).thenReturn(List.of(new ProjectContext(project,"moved",dir.resolve("moved"))));
    jobs.remove().run();verifyNoInteractions(chat);assertEquals("FAILED",goals.get(project,goal.id()).status());
  }
  @Test void actualPermissionFailureStopsAndShellCannotChangeGoalFromAnotherProject() {
    when(chat.execute(any(),anyString(),any(),any())).thenThrow(new ToolPermissionException("writeMultiFile",PermissionDecision.REQUIRE_APPROVAL));
    loop.run(project,goal.id());jobs.remove().run();assertEquals("WAITING_APPROVAL",goals.get(project,goal.id()).status());assertEquals(1,goals.get(project,goal.id()).attempts());
    var command=new GoalCommand(goals,loop,projects);var text=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(text));
    var cli=new picocli.CommandLine(command);assertEquals(0,cli.execute("show",goal.id()));assertTrue(text.toString().contains("WAITING_APPROVAL"));
    when(projects.currentContext()).thenReturn(new ProjectContext("00000000-0000-0000-0000-000000000002","other",dir));
    assertEquals(2,cli.execute("cancel",goal.id()));assertEquals("WAITING_APPROVAL",goals.get(project,goal.id()).status());
  }
  @Test void shellCreatesMultipleCriteriaAndGatewayRequiresAllBeforeCompletion() throws Exception {
    String hash=goal.sha256();
    var command=new GoalCommand(goals,loop,projects);var cli=new picocli.CommandLine(command);
    var text=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(text));
    String json="[{\"relativeFile\":\"A.txt\",\"sha256\":\""+hash+"\"},{\"relativeFile\":\"B.txt\",\"sha256\":\""+hash+"\"}]";
    assertEquals(0,cli.execute("create","two files","--criteria-json",json));
    var multi=goals.list(project).stream().filter(g->g.objective().equals("two files")).findFirst().orElseThrow();
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    when(chat.execute(any(),anyString(),any(),any())).thenAnswer(invocation->{
      String prompt=invocation.getArgument(1);assertTrue(prompt.contains("ALL"));assertTrue(prompt.contains("A.txt"));assertTrue(prompt.contains("B.txt"));
      var reservation=invocation.getArgument(3,OutputLimitRunBudget.LlmCallReservation.class);assertTrue(reservation.tryReserve());
      Files.writeString(dir.resolve(calls.incrementAndGet()==1?"A.txt":"B.txt"),"correct");
      return ChatExecutionResult.success("Complete",false);
    });
    loop.run(project,multi.id());jobs.remove().run();assertEquals("RUNNING",goals.get(project,multi.id()).status());
    jobs.remove().run();var saved=goals.get(project,multi.id());assertEquals("COMPLETED",saved.status());assertEquals(2,saved.attempts());assertEquals(2,saved.llmCallsUsed());
    assertEquals(List.of("UNVERIFIED","VERIFIED"),goals.attempts(project,multi.id()).stream().map(GoalRepository.Attempt::status).toList());
  }
  @Test void shellRejectsMixedMalformedAndTrailingCriteriaWithoutCreatingGoal() {
    String[] values={"[]", "null", "{}", "[] []", "[{\"relativeFile\":\"../escape\",\"sha256\":\""+goal.sha256()+"\"}]"};
    for(var value:values) {
      var command=new GoalCommand(goals,loop,projects);command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
      assertEquals(2,new picocli.CommandLine(command).execute("create","bad","--criteria-json",value));
    }
    var command=new GoalCommand(goals,loop,projects);command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(2,new picocli.CommandLine(command).execute("create","bad","--criteria-json","[]","--file","x","--sha256",goal.sha256()));
    assertEquals(1,goals.list(project).size());
  }
  @Test void reconciliationCannotReleaseQueuedOrExecutingGoalRun() {
    loop.run(project,goal.id());String run=goals.get(project,goal.id()).currentRunId();
    assertThrows(IllegalStateException.class,()->loop.reconcile(project,goal.id(),run));assertEquals("RUNNING",goals.get(project,goal.id()).status());
    when(chat.execute(any(),anyString(),any(),any())).thenAnswer(invocation->{
      assertThrows(IllegalStateException.class,()->loop.reconcile(project,goal.id(),run));return ChatExecutionResult.failed("stop");
    });
    jobs.remove().run();assertEquals("FAILED",goals.get(project,goal.id()).status());
  }
  @Test void shellRequiresAcknowledgementAndResumesUncertainGoalOnlyExplicitly() {
    var claim=goals.claim(project,goal.id());String run=goals.beginAttempt(claim);assertTrue(goals.reserveLlm(claim));
    var command=new GoalCommand(goals,loop,projects);command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));var cli=new picocli.CommandLine(command);
    assertEquals(2,cli.execute("reconcile",goal.id(),"--run-id",run));assertEquals("RUNNING",goals.get(project,goal.id()).status());
    command=new GoalCommand(goals,loop,projects);command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));cli=new picocli.CommandLine(command);
    assertEquals(0,cli.execute("reconcile",goal.id(),"--run-id",run,"--acknowledge-uncertain-side-effects"));
    assertEquals("PAUSED",goals.get(project,goal.id()).status());verifyNoInteractions(chat);assertTrue(jobs.isEmpty());
    when(chat.execute(any(),anyString(),any(),any())).thenAnswer(invocation->{assertTrue(invocation.getArgument(1,String.class).contains("unknown side effects"));return ChatExecutionResult.failed("stop");});
    loop.run(project,goal.id());jobs.remove().run();assertEquals(2,goals.get(project,goal.id()).attempts());assertEquals(1,goals.get(project,goal.id()).llmCallsUsed());
  }
}
