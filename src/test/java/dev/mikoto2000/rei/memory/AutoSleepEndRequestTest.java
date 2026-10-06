package dev.mikoto2000.rei.memory;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.*;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("integration")
class AutoSleepEndRequestTest {
  @TempDir Path dir;
  final String project="00000000-0000-0000-0000-000000000001";
  final Instant now=Instant.parse("2026-10-06T12:00:00Z");
  final MemoryProperties memory=new MemoryProperties(true,20,80,10,3,2000,60,null);
  final MemoryCandidateExtractor extractor=mock(MemoryCandidateExtractor.class);
  MemoryRepository repository() {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"));return new MemoryRepository(source,new MemoryService(source,memory));
  }
  SleepService sleep() {
    var resolution=mock(MemoryResolutionModel.class);when(resolution.resolve(any(),anyList())).thenReturn(new MemoryResolution(MemoryAction.NEW,List.of()));
    doAnswer(invocation->{
      List<ConversationTurnStore.Turn> batch=invocation.getArgument(0);
      return List.of(new MemoryCandidate(MemoryType.DECISION,MemoryScope.PROJECT,"agreed decision","agreed decision",.95,.9,
          List.of(batch.getFirst().runId()),List.of()));
    }).when(extractor).extract(anyList());
    return new SleepService(repository(),new ConversationTurnStore(dir.resolve("turns")),extractor,new MemoryResolver(resolution,memory),memory);
  }
  AutoSleepService auto(SleepService sleep,boolean shutdown) {
    return auto(sleep,shutdown,new DefaultAgentActivityTracker(Clock.fixed(now.minusSeconds(600),ZoneOffset.UTC)),Clock.fixed(now,ZoneOffset.UTC));
  }
  AutoSleepService auto(SleepService sleep,boolean shutdown,DefaultAgentActivityTracker activity,Clock clock) {
    var service=new AutoSleepService(sleep,memory,new AutoSleepProperties(true,Duration.ofSeconds(5),5,Duration.ofMinutes(1),
        "0 0 0 * * *","UTC",true,shutdown),activity,clock);
    var projects=mock(ProjectService.class);when(projects.completionProjects()).thenReturn(List.of(new ProjectContext(project,"p",dir)));
    service.setStartupSources(new FileSessionRepository(dir.resolve("sessions.json")),projects);return service;
  }
  AgentRunContext turn(String session) {
    var owner=new AgentRunContext("run-"+session,session,dir,project);
    var turns=new ConversationTurnStore(dir.resolve("turns"));turns.start(owner,"agreed decision");turns.finish(owner,ConversationTurnStore.Status.COMPLETED,"agreed");
    new FileSessionRepository(dir.resolve("sessions.json")).accept(new SessionMetadata(session,project,"ended",now,now),()->{});return owner;
  }
  @Test void shutdownPersistsWithoutModelAndRestartedIdleBypassesCronAndTurnThreshold() throws Exception {
    var owner=turn("ended");var first=sleep();var auto=auto(first,true);auto.afterTerminal(owner);auto.close();
    verify(extractor,never()).extract(anyList());assertEquals(1,first.pendingAutoSleepRequests().size());
    var restarted=sleep();try(var idle=auto(restarted,true)) {
      idle.tick();
      for(int i=0;i<200&&!restarted.pendingAutoSleepRequests().isEmpty();i++)Thread.sleep(10);
      assertEquals(1,repository().lastProcessed("ended"));idle.tick();
      assertTrue(restarted.pendingAutoSleepRequests().isEmpty());
    }
    verify(extractor,times(1)).extract(anyList());
  }
  @Test void revisionsCoalesceAcrossRestartAndCapacityIsAtomicAndOwnerBounded() throws Exception {
    var repository=repository();repository.requestAutoSleep("s",project,"session_end");var first=repository.pendingAutoSleepRequests().getFirst();
    repository.requestAutoSleep("s",project,"shutdown");var second=repository().pendingAutoSleepRequests().getFirst();assertEquals(first.revision()+1,second.revision());
    assertFalse(repository.completeAutoSleepRequest(first));assertFalse(repository.completeAutoSleepRequest(new MemoryRepository.AutoSleepRequest("s","other",second.revision(),"shutdown")));
    assertTrue(repository.completeAutoSleepRequest(second));
    for(int i=0;i<255;i++)repository.requestAutoSleep("queued"+i,project,"session_end");
    try(var pool=Executors.newFixedThreadPool(8)) {
      var jobs=new ArrayList<Future<Boolean>>();for(int i=0;i<8;i++){String id="race"+i;jobs.add(pool.submit(()->{
        try{repository.requestAutoSleep(id,project,"session_end");return true;}catch(RuntimeException full){return false;}
      }));}
      int accepted=0;for(var job:jobs)if(job.get())accepted++;assertEquals(1,accepted);
    }
    assertEquals(256,repository().pendingAutoSleepRequests().size());
    assertThrows(IllegalStateException.class,()->repository.requestAutoSleep("queued0","other","session_end"));
    assertThrows(IllegalArgumentException.class,()->repository.requestAutoSleep("project:00000000-0000-0000-0000-000000000002:chat:s",project,"session_end"));
    assertThrows(IllegalArgumentException.class,()->repository.requestAutoSleep("s",project,null));
  }
  @Test void actualSessionEndCliQueuesWithoutDispatchAndRetainsResumableHistory() throws Exception {
    var projects=new ProjectService(dir,new ProjectRegistry(dir.resolve("projects.json")));
    var saved=new FileSessionRepository(dir.resolve("sessions.json"));var lifecycle=new SessionLifecycle(saved,Clock.fixed(now,ZoneOffset.UTC));
    var sleep=sleep();try(var auto=auto(sleep,false);var client=projects.newClient().open()) {
      lifecycle.onEnded(auto::afterSessionEnd);
      var shell=new ShellConversationService(projects,lifecycle,(owner,prompt)->fail("end must not dispatch"));
      var session=shell.newConversation("ended");
      var command=new dev.mikoto2000.rei.ui.shell.SessionCommand(shell,new SessionQueryService(saved,new ConversationTurnStore(dir.resolve("turns"))),projects);
      var cli=new picocli.CommandLine(command);cli.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
      assertEquals(0,cli.execute("end"));assertNull(shell.currentSessionId());assertEquals(1,sleep.pendingAutoSleepRequests().size());
      assertEquals(session.projectId(),sleep.pendingAutoSleepRequests().getFirst().projectId());assertEquals(Optional.of(session),saved.findById(session.sessionId()));
      shell.resume(session.sessionId());assertEquals(session.sessionId(),shell.currentSessionId());
      assertThrows(dev.mikoto2000.rei.application.run.SessionConflictException.class,()->lifecycle.end(session.sessionId(),"other"));
      assertEquals(1,sleep.pendingAutoSleepRequests().size());verify(extractor,never()).extract(anyList());
    }
  }
  @Test void failedEndCallbackKeepsCurrentClientSelection() {
    var projects=new ProjectService(dir,new ProjectRegistry(dir.resolve("projects.json")));
    var saved=new FileSessionRepository(dir.resolve("sessions.json"));var lifecycle=new SessionLifecycle(saved,Clock.fixed(now,ZoneOffset.UTC));
    lifecycle.onEnded(session->{throw new IllegalStateException("storage unavailable");});
    try(var client=projects.newClient().open()) {
      var shell=new ShellConversationService(projects,lifecycle,(owner,prompt)->{});var session=shell.newConversation("keep");
      assertThrows(IllegalStateException.class,shell::end);assertEquals(session.sessionId(),shell.currentSessionId());
      assertEquals(Optional.of(session),saved.findById(session.sessionId()));
    }
  }
  @Test void providerFailureRetainsRequestAndDoesNotRetryDuringCooldown() throws Exception {
    var owner=turn("failure");var sleep=sleep();var entered=new CountDownLatch(1);
    doAnswer(invocation->{entered.countDown();throw new IllegalStateException("private provider detail");}).when(extractor).extract(anyList());
    try(var auto=auto(sleep,false)) {
      auto.afterSessionEnd(new SessionMetadata(owner.conversationId(),project,"ended",now,now));auto.tick();assertTrue(entered.await(2,TimeUnit.SECONDS));
      for(int i=0;i<200&&repository().history(project,10).isEmpty();i++)Thread.sleep(10);
      auto.tick();assertEquals(0,repository().lastProcessed("failure"));assertEquals(1,sleep.pendingAutoSleepRequests().size());
      assertEquals("FAILED",repository().history(project,10).getFirst().status());verify(extractor,times(1)).extract(anyList());
    }
  }
  @Test void newActivityCancelsInFlightSleepWithoutLosingDurableRequest() throws Exception {
    var owner=turn("cancelled");var sleep=sleep();var entered=new CountDownLatch(1);
    doAnswer(invocation->{
      entered.countDown();
      try{new CountDownLatch(1).await();return List.of();}
      catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("cancelled provider");}
    }).when(extractor).extract(anyList());
    var activity=new DefaultAgentActivityTracker(Clock.fixed(now.minusSeconds(600),ZoneOffset.UTC));
    try(var auto=auto(sleep,false,activity,Clock.fixed(now,ZoneOffset.UTC))) {
      auto.afterSessionEnd(new SessionMetadata(owner.conversationId(),project,"ended",now,now));auto.tick();assertTrue(entered.await(2,TimeUnit.SECONDS));
      activity.recordUserActivity(now);auto.tick();
      for(int i=0;i<200&&repository().history(project,10).isEmpty();i++)Thread.sleep(10);
      assertEquals(0,repository().lastProcessed("cancelled"));assertEquals(1,sleep.pendingAutoSleepRequests().size());
      assertEquals("CANCELLED",repository().history(project,10).getFirst().status());auto.tick();verify(extractor,times(1)).extract(anyList());
    }
  }
  @Test void disabledFlagsAndUnknownProjectsCannotDispatchStoredExitRequests() {
    var owner=turn("off");var sleep=sleep();
    var disabled=new AutoSleepService(sleep,memory,new AutoSleepProperties(true,Duration.ofSeconds(5),5,Duration.ofMinutes(1)),
        new DefaultAgentActivityTracker(Clock.fixed(now.minusSeconds(600),ZoneOffset.UTC)),Clock.fixed(now,ZoneOffset.UTC));
    disabled.afterSessionEnd(new SessionMetadata("off",project,"ended",now,now));disabled.afterTerminal(owner);disabled.close();disabled.close();
    assertTrue(sleep.pendingAutoSleepRequests().isEmpty());
    sleep.requestAutoSleep("off",project,"shutdown");sleep.requestAutoSleep("unknown","other","session_end");
    try(var auto=auto(sleep,false)){auto.tick();assertEquals(2,sleep.pendingAutoSleepRequests().size());}
    verify(extractor,never()).extract(anyList());
  }
  @Test void queueControlsUseSelectedProjectAndExactRevisionWithoutCallingModel() {
    var sleep=sleep();sleep.requestAutoSleep("visible",project,"session_end");sleep.requestAutoSleep("private","other","session_end");
    var projects=mock(ProjectService.class);var context=new ProjectContext(project,"p",dir);when(projects.currentContext()).thenReturn(context);
    var support=new dev.mikoto2000.rei.memory.command.MemoryCommandSupport(repository(),sleep,projects,memory);
    var cli=new picocli.CommandLine(new dev.mikoto2000.rei.memory.command.SleepCommand(sleep,support,projects,new dev.mikoto2000.rei.core.service.CommandCancellationService()));
    var output=new java.io.StringWriter();cli.setOut(new java.io.PrintWriter(output));
    assertEquals(0,cli.execute("requests"));assertTrue(output.toString().contains("visible"));assertFalse(output.toString().contains("private"));
    assertEquals(2,cli.execute("cancel-request","visible","--revision","2"));assertEquals(2,sleep.pendingAutoSleepRequests().size());
    assertEquals(2,cli.execute("cancel-request","private","--revision","1"));
    assertEquals(0,cli.execute("cancel-request","visible","--revision","1"));assertEquals(1,sleep.pendingAutoSleepRequests().size());
    verify(extractor,never()).extract(anyList());
  }
  @Test void endFlagsBindExplicitlyAndOldConstructorsRemainDisabled() {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("rei.memory.auto-sleep.enabled","true",
        "rei.memory.auto-sleep.on-session-end","true","rei.memory.auto-sleep.on-shutdown","true"));
    var bound=new org.springframework.boot.context.properties.bind.Binder(source).bind("rei.memory.auto-sleep",
        org.springframework.boot.context.properties.bind.Bindable.of(AutoSleepProperties.class)).get();
    assertTrue(bound.enabled());assertTrue(bound.onSessionEnd());assertTrue(bound.onShutdown());
    var legacy=new AutoSleepProperties(true,Duration.ofSeconds(5),5,Duration.ofMinutes(1));assertFalse(legacy.onSessionEnd());assertFalse(legacy.onShutdown());
  }
  @Test void durableRequestsStillRespectBusyAndMinimumIdleBeforeCallingModel() {
    var owner=turn("busy");var sleep=sleep();var activity=new DefaultAgentActivityTracker(Clock.fixed(now.minusSeconds(600),ZoneOffset.UTC));
    activity.recordAgentStarted(now);
    try(var auto=auto(sleep,false,activity,Clock.fixed(now,ZoneOffset.UTC))) {
      auto.afterSessionEnd(new SessionMetadata(owner.conversationId(),project,"ended",now,now));auto.tick();
      activity.recordAgentCompleted(now);auto.tick();assertEquals(1,sleep.pendingAutoSleepRequests().size());
      assertEquals(0,repository().lastProcessed("busy"));verify(extractor,never()).extract(anyList());
    }
  }
}
