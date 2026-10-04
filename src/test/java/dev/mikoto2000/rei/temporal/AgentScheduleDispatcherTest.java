package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;

@Tag("integration")
class AgentScheduleDispatcherTest {
  @TempDir Path dir;
  PersistentAgentScheduler schedules;
  String id;
  @BeforeEach void setup() {
    schedules=new PersistentAgentScheduler(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("schedule.db")),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
    try(var scope=AgentRunScope.open(new AgentRunContext("r","s",dir,"00000000-0000-0000-0000-000000000001"))) {id=schedules.scheduleAfter(Duration.ZERO,"inspect build","s").id();}
    schedules.activate("00000000-0000-0000-0000-000000000001",id);
  }
  AgentScheduleDispatcher dispatcher(boolean enabled,boolean permission,AgentScheduleDispatcher.Gateway gateway) {
    return new AgentScheduleDispatcher(schedules,new AgentSchedulerProperties(enabled),new ToolPermissionProperties(permission,null,null,null),gateway);
  }
  @Test void neitherDefaultDisabledNorDisabledPolicyCanDispatch() {
    var calls=new AtomicInteger();var a=dispatcher(false,true,(entry,done)->calls.incrementAndGet());
    var b=dispatcher(true,false,(entry,done)->calls.incrementAndGet());a.tick();b.tick();
    assertEquals(0,calls.get());assertEquals("SCHEDULED",schedules.get("00000000-0000-0000-0000-000000000001",id).status());
  }
  @Test void dispatchIsBoundedAndRecordsActualCompletionWithoutRepeating() {
    var calls=new AtomicInteger();var completion=new AtomicReference<Consumer<ChatExecutionResult>>();
    var dispatcher=dispatcher(true,true,(entry,done)->{calls.incrementAndGet();completion.set(done);});
    dispatcher.tick();dispatcher.tick();assertEquals(1,calls.get());assertEquals("RUNNING",schedules.get("00000000-0000-0000-0000-000000000001",id).status());
    completion.get().accept(ChatExecutionResult.success("Done",false));dispatcher.tick();
    assertEquals(1,calls.get());assertEquals("COMPLETED",schedules.get("00000000-0000-0000-0000-000000000001",id).status());assertEquals("Done",schedules.get("00000000-0000-0000-0000-000000000001",id).outcome());
  }
  @Test void dispatchFailureAndCancellationRemainTerminal() {
    var failed=dispatcher(true,true,(entry,done)->{throw new IllegalStateException("Project relocated");});failed.tick();failed.tick();
    assertEquals("FAILED",schedules.get("00000000-0000-0000-0000-000000000001",id).status());
    try(var scope=AgentRunScope.open(new AgentRunContext("r","s",dir,"00000000-0000-0000-0000-000000000001"))) {id=schedules.scheduleAfter(Duration.ZERO,"x","s").id();}
    schedules.activate("00000000-0000-0000-0000-000000000001",id);dispatcher(true,true,(entry,done)->done.accept(ChatExecutionResult.cancelled())).tick();
    assertEquals("CANCELLED",schedules.get("00000000-0000-0000-0000-000000000001",id).status());
  }
  @Test void shellControlsAreProjectScopedAndNeverDispatchDirectly() {
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.currentContext()).thenReturn(new dev.mikoto2000.rei.core.project.ProjectContext("00000000-0000-0000-0000-000000000001","test",dir));
    var command=new TimerCommand(schedules,projects);var writer=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(writer));
    var cli=new picocli.CommandLine(command);assertEquals(0,cli.execute("show",id));assertTrue(writer.toString().contains("SCHEDULED"));
    assertEquals(0,cli.execute("cancel",id));assertEquals("CANCELLED",schedules.get("00000000-0000-0000-0000-000000000001",id).status());
    assertEquals(2,cli.execute("activate",id));
  }
  @Test void actualGatewayUsesCapturedOwnerAndExistingProjectFifo() {
    String project=schedules.get("00000000-0000-0000-0000-000000000001",id).projectId();
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.registeredProjects()).thenReturn(List.of(new dev.mikoto2000.rei.core.project.ProjectContext(project,"test",dir)));
    var sessions=org.mockito.Mockito.mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    org.mockito.Mockito.when(sessions.findById("s")).thenReturn(Optional.of(new dev.mikoto2000.rei.application.session.SessionMetadata("s",project,"test",Instant.EPOCH,Instant.EPOCH)));
    var chat=org.mockito.Mockito.mock(ChatExecutionService.class);
    org.mockito.Mockito.when(chat.execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("inspect build"),org.mockito.ArgumentMatchers.any()))
        .thenReturn(ChatExecutionResult.success("Actual completion",false));
    var jobs=new ArrayDeque<Runnable>();var order=new ArrayList<String>();
    var router=new ConversationInputRouter(jobs::add,(owner,prompt,queue)->order.add("existing"));
    router.submit(new AgentRunContext("first","another-session",dir,project),"existing work");
    var dispatcher=new AgentScheduleDispatcher(schedules,new AgentSchedulerProperties(true),new ToolPermissionProperties(true,null,null,null),projects,sessions,router,chat);
    dispatcher.tick();org.mockito.Mockito.verifyNoInteractions(chat);assertEquals(1,jobs.size());
    jobs.remove().run();assertEquals(List.of("existing"),order);assertEquals(1,jobs.size());jobs.remove().run();
    var context=org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
    org.mockito.Mockito.verify(chat).execute(context.capture(),org.mockito.ArgumentMatchers.eq("inspect build"),org.mockito.ArgumentMatchers.any());
    assertEquals(project,context.getValue().projectId());assertEquals("s",context.getValue().conversationId());assertEquals(dir.toAbsolutePath(),context.getValue().projectRoot());
    assertEquals("COMPLETED",schedules.get(project,id).status());
  }
  record Harness(dev.mikoto2000.rei.core.project.ProjectService projects,ChatExecutionService chat,ConversationInputRouter router,ArrayDeque<Runnable> jobs,AgentScheduleDispatcher dispatcher) {}
  Harness harness() {
    String project="00000000-0000-0000-0000-000000000001";
    var projects=mock(dev.mikoto2000.rei.core.project.ProjectService.class);var context=new dev.mikoto2000.rei.core.project.ProjectContext(project,"test",dir);
    when(projects.currentContext()).thenReturn(context);when(projects.registeredProjects()).thenReturn(List.of(context));
    var sessions=mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    when(sessions.findById("s")).thenReturn(Optional.of(new dev.mikoto2000.rei.application.session.SessionMetadata("s",project,"test",Instant.EPOCH,Instant.EPOCH)));
    var chat=mock(ChatExecutionService.class);var jobs=new ArrayDeque<Runnable>();var router=new ConversationInputRouter(jobs::add,(owner,prompt,queue)->{});
    return new Harness(projects,chat,router,jobs,new AgentScheduleDispatcher(schedules,new AgentSchedulerProperties(true),new ToolPermissionProperties(true,null,null,null),projects,sessions,router,chat));
  }
  @Test void currentQueuedAndExecutingSchedulesCannotBeReconciled() {
    var h=harness();String project="00000000-0000-0000-0000-000000000001";h.dispatcher().tick();String run=schedules.get(project,id).runId();
    assertThrows(IllegalStateException.class,()->h.dispatcher().reconcile(project,id,run));
    when(h.chat().execute(any(),anyString(),any())).thenAnswer(invocation->{assertThrows(IllegalStateException.class,()->h.dispatcher().reconcile(project,id,run));return ChatExecutionResult.success("done",false);});
    h.jobs().remove().run();assertEquals("COMPLETED",schedules.get(project,id).status());
  }
  @Test void shellReconciliationAfterQueueRemovalReleasesDispatcherWithoutReplayingOldJob() {
    var h=harness();String project="00000000-0000-0000-0000-000000000001";h.dispatcher().tick();String run=schedules.get(project,id).runId();assertTrue(h.router().cancelQueued(run));
    var command=new TimerCommand(schedules,h.projects(),h.dispatcher());command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(2,new picocli.CommandLine(command).execute("reconcile",id,"--run-id",run));
    command=new TimerCommand(schedules,h.projects(),h.dispatcher());command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(0,new picocli.CommandLine(command).execute("reconcile",id,"--run-id",run,"--acknowledge-uncertain-side-effects"));
    assertEquals("FAILED",schedules.get(project,id).status());verifyNoInteractions(h.chat());
    String next;try(var scope=AgentRunScope.open(new AgentRunContext("source","s",dir,project))){next=schedules.scheduleAfter(Duration.ZERO,"next","s").id();}
    schedules.activate(project,next);h.dispatcher().tick();
    h.jobs().remove().run();verifyNoInteractions(h.chat());
    when(h.chat().execute(any(),eq("next"),any())).thenReturn(ChatExecutionResult.success("new",false));h.jobs().remove().run();assertEquals("COMPLETED",schedules.get(project,next).status());
  }
  @Test void staleCompletionCannotReleaseNewDispatcherClaim() {
    String project="00000000-0000-0000-0000-000000000001";var callbacks=new ArrayList<Consumer<ChatExecutionResult>>();
    var dispatcher=new AgentScheduleDispatcher(schedules,new AgentSchedulerProperties(true),new ToolPermissionProperties(true,null,null,null),(entry,done)->callbacks.add(done),entry->false);
    dispatcher.tick();String run=schedules.get(project,id).runId();dispatcher.reconcile(project,id,run);
    String next,third;
    try(var scope=AgentRunScope.open(new AgentRunContext("source","s",dir,project))){next=schedules.scheduleAfter(Duration.ZERO,"next","s").id();}
    try(var scope=AgentRunScope.open(new AgentRunContext("source","other",dir,project))){third=schedules.scheduleAfter(Duration.ZERO,"third","other").id();}
    schedules.activate(project,next);dispatcher.tick();schedules.activate(project,third);
    callbacks.getFirst().accept(ChatExecutionResult.success("late",false));dispatcher.tick();assertEquals(2,callbacks.size());assertEquals("FAILED",schedules.get(project,id).status());
    callbacks.get(1).accept(ChatExecutionResult.success("new",false));dispatcher.tick();assertEquals(3,callbacks.size());
  }
}
