package dev.mikoto2000.rei.application.run;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.task.TaskService;

class IntegrationRunSubmitServiceTest {
  @TempDir Path directory;
  @Test void implicitOAuthOperationsAreQueuedAndRetainClientOwnership()throws Exception {
    var projects=new ProjectRegistry(directory.resolve("projects.json"));var project=projects.resolve(directory);var lifecycle=mock(SessionLifecycle.class);var registry=new RunRegistry(Clock.systemUTC());var bus=new InMemoryAgentEventBus();var events=new AgentEventFactory(Clock.systemUTC());var cancellation=new CommandCancellationService();var pending=new ArrayDeque<Runnable>();var router=new ConversationInputRouter(pending::add,(context,prompt,queue)->{throw new AssertionError();});var calendar=mock(GoogleCalendarService.class);var tasks=mock(TaskService.class);when(tasks.listOpen(any())).thenAnswer(call->{assertEquals(project.id(),AgentRunScope.current().projectId());assertEquals("session",AgentRunScope.current().conversationId());return java.util.List.of();});
    try(var runs=new RunService(registry,bus,events,cancellation,router::cancelQueued)){
      var service=new IntegrationRunSubmitService(projects,lifecycle,registry,runs,router,cancellation,events,bus,calendar,tasks);var operation=service.listGoogleTasks(project.id(),"session",null,null,null);verifyNoInteractions(tasks);verify(lifecycle).validate("session",project.id());pending.remove().run();assertEquals(RunStatus.COMPLETED,runs.get(operation.runId()).status());verify(tasks).listOpen(any());assertNull(AgentRunScope.current());
    }
  }
  @Test void authorizationBeginsOnlyInsideTheCapturedWebRun() throws Exception {
    var projects=new ProjectRegistry(directory.resolve("projects.json"));var project=projects.resolve(directory);var lifecycle=mock(SessionLifecycle.class);
    var registry=new RunRegistry(Clock.systemUTC());var bus=new InMemoryAgentEventBus();var events=new AgentEventFactory(Clock.systemUTC());var cancellation=new CommandCancellationService();
    var pending=new ArrayDeque<Runnable>();var router=new ConversationInputRouter(pending::add,(context,prompt,queue)->{throw new AssertionError("OAuth must not execute chat");});
    var context=new AgentRunContext("run","session",directory,project.id(),AgentRunContext.RequestSource.WEB);
    when(lifecycle.submit(eq(project),eq("session"),eq("google.calendar.authorize"),eq(AgentRunContext.RequestSource.WEB),eq(AgentRunContext.Mode.EXCLUSIVE),eq(false),eq("key"),anyString(),any())).thenAnswer(call->{((java.util.function.Consumer<AgentRunContext>)call.getArgument(8)).accept(context);return context;});
    var calendar=mock(GoogleCalendarService.class);var tasks=mock(TaskService.class);
    doAnswer(call->{assertEquals(context,AgentRunScope.current());return null;}).when(calendar).authorize();
    try(var runs=new RunService(registry,bus,events,cancellation,router::cancelQueued)) {
      var service=new IntegrationRunSubmitService(projects,lifecycle,registry,runs,router,cancellation,events,bus,calendar,tasks);
      assertEquals(context,service.authorizeCalendar(project.id(),"session","key"));verifyNoInteractions(calendar,tasks);
      pending.remove().run();verify(calendar).authorize();assertEquals(RunStatus.COMPLETED,runs.get("run").status());assertNull(AgentRunScope.current());
      assertThrows(IllegalArgumentException.class,()->service.authorizeTasks(project.id(),"session","invalid key"));
    }
  }
}
