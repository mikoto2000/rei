package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.event.AgentEvent;

@Tag("integration")
class EventTriggerTest {
  @TempDir Path dir;
  final String project=UUID.randomUUID().toString();
  final Instant now=Instant.parse("2026-10-04T00:00:00Z");
  PersistentAgentScheduler scheduler(Instant time) {
    return new PersistentAgentScheduler(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("events.db")),Clock.fixed(time,ZoneOffset.UTC));
  }
  String create(PersistentAgentScheduler scheduler) {
    try(var scope=AgentRunScope.open(new AgentRunContext("parent","session",dir,project))) {
      return new SchedulerTools(scheduler).scheduleOnEvent("source","AGENT_RUN_COMPLETED","1h","Continue","session").id();
    }
  }
  AgentEvent event(String project,String session,String run,Instant time,AgentEventType type) {
    return new AgentEvent(UUID.randomUUID().toString(),0,time,type,1,session,null,run,null,null,
        new AgentRunCompletedPayload(run,1),project);
  }
  AgentEvent completed(){return event(project,"session","source",now.plusSeconds(1),AgentEventType.AGENT_RUN_COMPLETED);}
  @Test void explicitActivationExactOwnershipAndDuplicateSuppression() {
    var scheduler=scheduler(now.plusSeconds(2));String id=create(scheduler);
    scheduler.signalEvent(completed());assertTrue(scheduler.claimDue().isEmpty());
    scheduler.activate(project,id);assertEquals("WAITING_EVENT",scheduler.get(project,id).status());
    scheduler.signalEvent(completed()); // Before activation, even when replayed.
    scheduler.signalEvent(event("other","session","source",now.plusSeconds(2),AgentEventType.AGENT_RUN_COMPLETED));
    scheduler.signalEvent(event(project,"other","source",now.plusSeconds(2),AgentEventType.AGENT_RUN_COMPLETED));
    scheduler.signalEvent(event(project,"session","other",now.plusSeconds(2),AgentEventType.AGENT_RUN_COMPLETED));
    assertTrue(scheduler.claimDue().isEmpty());
    var later=scheduler(now.plusSeconds(4));var matching=event(project,"session","source",now.plusSeconds(3),AgentEventType.AGENT_RUN_COMPLETED);
    later.signalEvent(matching);later.signalEvent(matching);var claim=later.claimDue().orElseThrow();
    assertEquals(matching.id(),later.eventTrigger(project,id).orElseThrow().matchedEventId());
    later.finish(claim,"COMPLETED","ok");later.signalEvent(matching);assertTrue(later.claimDue().isEmpty());
    assertEquals(1,later.history(project,id).stream().filter(h->h.status().equals("SCHEDULED")).count());
  }
  @Test void expiryAndCancellationNeverDispatchByTimeAlone() {
    var scheduler=scheduler(now);String id=create(scheduler);scheduler.activate(project,id);
    assertTrue(scheduler(now.plusSeconds(3600)).claimDue().isEmpty());
    assertEquals("FAILED",scheduler.get(project,id).status());
    String cancelled=create(scheduler);scheduler.activate(project,cancelled);scheduler.cancel(project,cancelled);
    scheduler(now.plusSeconds(2)).signalEvent(completed());assertTrue(scheduler(now.plusSeconds(2)).claimDue().isEmpty());
    assertThrows(IllegalArgumentException.class,()->scheduler.eventTrigger("other",cancelled));
  }
  @Test void restartReplaysPersistedEventAndNeverReplaysAnUncertainClaim() {
    var first=scheduler(now);String id=create(first);first.activate(project,id);
    var store=new ProjectAgentEventStore(dir.resolve("history"));store.append(completed());
    var restarted=scheduler(now.plusSeconds(2));var bus=new InMemoryAgentEventBus();
    try(var triggers=new AgentEventTriggerService(restarted,bus,store)) {
      triggers.tick();var claim=restarted.claimDue().orElseThrow();assertEquals(id,claim.task().id());
      triggers.tick();bus.publish(completed());assertTrue(restarted.claimDue().isEmpty());
    }
    try(var triggers=new AgentEventTriggerService(scheduler(now.plusSeconds(3)),bus,store)) {
      triggers.tick();assertTrue(scheduler(now.plusSeconds(3)).claimDue().isEmpty());
    }
  }
  @Test void liveBusSubscriptionIsClosedAndIgnoresUnrelatedEvents() {
    var first=scheduler(now);String id=create(first);first.activate(project,id);
    var bus=new InMemoryAgentEventBus();var later=scheduler(now.plusSeconds(2));
    var service=new AgentEventTriggerService(later,bus,new ProjectAgentEventStore(dir.resolve("history")));
    bus.publish(event(project,"session","source",now.plusSeconds(1),AgentEventType.AGENT_RUN_FAILED));
    assertTrue(later.claimDue().isEmpty());bus.publish(completed());assertTrue(later.claimDue().isPresent());
    service.close();String second=create(first);first.activate(project,second);bus.publish(completed());
    assertEquals("WAITING_EVENT",later.get(project,second).status());
  }
  @Test void invalidConfigurationIsRejectedWithoutPartialRows() {
    var scheduler=scheduler(now);
    try(var scope=AgentRunScope.open(new AgentRunContext("parent","session",dir,project))) {
      assertThrows(IllegalArgumentException.class,()->scheduler.scheduleOnEvent("source",AgentEventType.TOOL_STARTED,Duration.ofHours(1),"x","session"));
      assertThrows(IllegalArgumentException.class,()->scheduler.scheduleOnEvent("",AgentEventType.AGENT_RUN_COMPLETED,Duration.ofHours(1),"x","session"));
      assertThrows(IllegalArgumentException.class,()->scheduler.scheduleOnEvent("source",AgentEventType.AGENT_RUN_COMPLETED,Duration.ZERO,"x","session"));
      assertThrows(IllegalArgumentException.class,()->new SchedulerTools(scheduler).scheduleOnEvent("source","AGENT_RUN_COMPLETED","1h","x","other"));
    }
    assertTrue(scheduler.list(project).isEmpty());
  }
  @Test void persistedReplayCursorContinuesAcrossMultiplePagesAndRestarts() {
    var first=scheduler(now);String id=create(first);first.activate(project,id);
    var store=new ProjectAgentEventStore(dir.resolve("history"));
    for(int i=0;i<300;i++)store.append(event(project,"session","unrelated-"+i,now.plusSeconds(1),AgentEventType.AGENT_RUN_COMPLETED));
    store.append(completed());var later=scheduler(now.plusSeconds(2));var bus=new InMemoryAgentEventBus();
    try(var triggers=new AgentEventTriggerService(later,bus,store)) {triggers.tick();assertTrue(later.claimDue().isEmpty());}
    long offset=later.replayCursor(project).offset();assertTrue(offset>0);
    try(var triggers=new AgentEventTriggerService(scheduler(now.plusSeconds(2)),bus,store)) {
      triggers.tick();assertTrue(later.claimDue().isEmpty());assertTrue(later.replayCursor(project).offset()>offset);
      triggers.tick();assertEquals(id,later.claimDue().orElseThrow().task().id());
    }
  }
  @Test void concurrentDuplicateSignalsHaveOneTransitionAndOneClaim() throws Exception {
    var first=scheduler(now);String id=create(first);first.activate(project,id);
    var one=scheduler(now.plusSeconds(2));var two=scheduler(now.plusSeconds(2));var event=completed();
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a=pool.submit(()->one.signalEvent(event));var b=pool.submit(()->two.signalEvent(event));a.get();b.get();
      var c=pool.submit(one::claimDue);var d=pool.submit(two::claimDue);
      assertEquals(1,(c.get().isPresent()?1:0)+(d.get().isPresent()?1:0));
    }
    assertEquals(1,one.history(project,id).stream().filter(h->h.status().equals("SCHEDULED")).count());
  }
  @Test void eventDispatchUsesCapturedOwnerAndExistingFifoOnlyWhenEnabled() {
    var first=scheduler(now);String id=create(first);first.activate(project,id);var later=scheduler(now.plusSeconds(2));
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    org.mockito.Mockito.when(projects.registeredProjects()).thenReturn(java.util.List.of(new dev.mikoto2000.rei.core.project.ProjectContext(project,"test",dir)));
    var sessions=org.mockito.Mockito.mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    org.mockito.Mockito.when(sessions.findById("session")).thenReturn(java.util.Optional.of(new dev.mikoto2000.rei.application.session.SessionMetadata("session",project,"test",now,now)));
    var chat=org.mockito.Mockito.mock(ChatExecutionService.class);
    org.mockito.Mockito.when(chat.execute(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("Continue"),org.mockito.ArgumentMatchers.any()))
        .thenReturn(ChatExecutionResult.success("done",false));
    var jobs=new java.util.ArrayDeque<Runnable>();var router=new ConversationInputRouter(jobs::add,(owner,prompt,queue)->{});
    var permissions=new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(true,null,null,null);
    var dispatcher=new AgentScheduleDispatcher(later,new AgentSchedulerProperties(true),permissions,projects,sessions,router,chat);
    dispatcher.tick();assertTrue(jobs.isEmpty());later.signalEvent(completed());
    var disabled=new AgentScheduleDispatcher(later,new AgentSchedulerProperties(false),permissions,projects,sessions,router,chat);
    disabled.tick();assertTrue(jobs.isEmpty());dispatcher.tick();org.mockito.Mockito.verifyNoInteractions(chat);
    jobs.remove().run();assertTrue(jobs.isEmpty());
    var capture=org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
    org.mockito.Mockito.verify(chat).execute(capture.capture(),org.mockito.ArgumentMatchers.eq("Continue"),org.mockito.ArgumentMatchers.any());
    assertEquals(project,capture.getValue().projectId());assertEquals("session",capture.getValue().conversationId());
    assertEquals("COMPLETED",later.get(project,id).status());dispatcher.tick();assertTrue(jobs.isEmpty());
  }
  @Test void cursorFromBeforeNewActivationCannotSkipItsReplay() {
    var first=scheduler(now);String id=create(first);first.activate(project,id);
    var old=first.replayCursor(project);String next=create(first);first.activate(project,next);
    first.saveReplayCursor(project,old,999,false);assertEquals(0,first.replayCursor(project).offset());
  }
  @Test void replayNeverImportsEventOwnershipFromAnotherProjectFile() throws Exception {
    var first=scheduler(now);String id=create(first);first.activate(project,id);
    String other=UUID.randomUUID().toString();String otherId;
    try(var scope=AgentRunScope.open(new AgentRunContext("parent","session",dir,other))) {
      otherId=first.scheduleOnEvent("source",AgentEventType.AGENT_RUN_COMPLETED,Duration.ofHours(1),"other","session").id();
    }
    first.activate(other,otherId);
    var source=new ProjectAgentEventStore(dir.resolve("source"));source.append(event(other,"session","source",now.plusSeconds(1),AgentEventType.AGENT_RUN_COMPLETED));
    Path target=dir.resolve("history/projects").resolve(project).resolve("events/events.jsonl");java.nio.file.Files.createDirectories(target.getParent());
    java.nio.file.Files.copy(dir.resolve("source/projects").resolve(other).resolve("events/events.jsonl"),target);
    var later=scheduler(now.plusSeconds(2));
    try(var service=new AgentEventTriggerService(later,new InMemoryAgentEventBus(),new ProjectAgentEventStore(dir.resolve("history")))) {
      service.tick();assertTrue(later.claimDue().isEmpty());
      assertEquals("WAITING_EVENT",later.get(project,id).status());assertEquals("WAITING_EVENT",later.get(other,otherId).status());
    }
  }
}
