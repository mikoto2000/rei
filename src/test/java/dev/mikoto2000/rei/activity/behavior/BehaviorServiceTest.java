package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.*;
import dev.mikoto2000.rei.topic.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.mikoto2000.rei.activity.behavior.BehaviorEvaluatorTest.*;

class BehaviorServiceTest {
  @TempDir java.nio.file.Path directory;
  final BehaviorProperties properties=new BehaviorProperties();
  final ActivityProperties activity=new ActivityProperties();
  final ActivityCapture capture=mock(ActivityCapture.class);
  final ActivityStore store=mock(ActivityStore.class);
  final BehaviorStateStore state=mock(BehaviorStateStore.class);
  final BehaviorMessageGenerator generator=mock(BehaviorMessageGenerator.class);
  final AgentMessagePublisher publisher=mock(AgentMessagePublisher.class);
  final AgentActivityTracker tracker=mock(AgentActivityTracker.class);
  final Clock clock=Clock.fixed(START.plusSeconds(3600),ZoneOffset.UTC);
  BehaviorService service() throws Exception {
    var r=record(0,3600,"social");when(store.findBetween(any(),any())).thenReturn(sessions(List.of(r)));
    when(store.findRecordsBetween(any(),any())).thenReturn(List.of(r));when(state.load()).thenReturn(BehaviorState.empty());
    when(generator.generate(any())).thenReturn("観測できた範囲ではSNSが長く続いているみたい。少し区切りをつけようか。");
    activity.setEnabled(true);return new BehaviorService(properties,activity,capture,store,state,generator,publisher,tracker,clock);
  }
  @Test void noneAssessmentNeverGeneratesOrPublishesHistory() throws Exception {
    var service=service();service.setEnabled(true);
    when(store.findBetween(any(),any())).thenReturn(List.of());when(store.findRecordsBetween(any(),any())).thenReturn(List.of());
    service.tick();verifyNoInteractions(generator,publisher);
  }
  @Test void oneGenerationFeedsDisplayConversationAndTimelineWithSharedId() throws Exception {
    service();
    var logs=new dev.mikoto2000.rei.conversation.ConversationLogStore(directory,clock,
        new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    var events=new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var bus=new dev.mikoto2000.rei.event.InMemoryAgentEventBus();bus.subscribe(events::add);
    var delivery=new EventAgentMessagePublisher(logs,new dev.mikoto2000.rei.event.AgentEventFactory(clock),bus,tracker,
        mock(dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator.class));
    var service=new BehaviorService(properties,activity,capture,store,state,generator,delivery,tracker,clock);
    service.setEnabled(true);service.tick();service.tick();
    verify(generator,times(1)).generate(any());
    var entries=logs.readConversation("chat:main");assertEquals(1,entries.size());assertEquals(3,events.size());
    verify(state).appendEvent(argThat(e->e.outcome()==BehaviorTimelineEvent.Outcome.EMITTED
        && e.id().equals(entries.getFirst().sourceId()) && e.timestamp().equals(entries.getFirst().timestamp().toInstant())));
    assertEquals("BEHAVIOR_NOTIFICATION",entries.getFirst().source());
  }
  @Test void disabledDoesNotReadEvaluatePersistOrCallLlm() throws Exception {
    var service=service();clearInvocations(store,state,generator);service.tick();verifyNoInteractions(store,state,generator,publisher);
  }
  @Test void eligibleNotificationIsReservedBeforeLlmAndDeliveredViaExistingPublisher() throws Exception {
    var service=service();service.setEnabled(true);service.tick();service.tick();
    var order=inOrder(state,generator,publisher);order.verify(state).load();order.verify(state).save(any(),any());order.verify(generator).generate(any());order.verify(publisher).publish(any());
    verify(generator,times(1)).generate(any());assertTrue(service.status().contains("cooldownUntil="));
  }
  @Test void busyOrPausedSuppressesLlm() throws Exception {
    var service=service();service.setEnabled(true);when(tracker.isAgentBusy()).thenReturn(true);service.tick();verifyNoInteractions(generator,publisher);
    clearInvocations(store,state);when(capture.isPaused()).thenReturn(true);service.tick();verifyNoInteractions(store,state);
  }
  @Test void persistenceFailureFailsClosedAndDoesNotEscapeTick() throws Exception {
    var service=service();service.setEnabled(true);doThrow(new IllegalStateException("private detail")).when(state).save(any(),any());
    assertDoesNotThrow(service::tick);verifyNoInteractions(generator,publisher);assertTrue(service.status().contains("FAILED"));
  }
  @Test void llmFailureDoesNotStormOrStopCapture() throws Exception {
    var service=service();service.setEnabled(true);when(generator.generate(any())).thenThrow(new IllegalStateException("private payload"));
    assertDoesNotThrow(service::tick);service.tick();verify(generator,times(1)).generate(any());verifyNoInteractions(publisher);verify(capture,never()).pause();
  }
  @Test void manualEvaluationWorksDisabledWithoutNotificationsOrStateWrites() throws Exception {
    var service=service();assertEquals(BehaviorSeverity.WARNING,service.evaluate().severity());verifyNoInteractions(generator,publisher,state);
  }
  @Test void offDuringSlowGenerationIsNonblockingAndDiscardsResult() throws Exception {
    var service=service();service.setEnabled(true);
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(generator.generate(any())).thenAnswer(inv->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "message";});
    try(var executor=Executors.newSingleThreadExecutor()) {
      var future=executor.submit(service::tick);assertTrue(entered.await(5,TimeUnit.SECONDS));
      assertTimeoutPreemptively(Duration.ofSeconds(1),()->service.setEnabled(false));release.countDown();future.get(5,TimeUnit.SECONDS);
    } finally {release.countDown();}
    verifyNoInteractions(publisher);
  }
  @Test void concurrentTickDoesNotQueueAnotherLlm() throws Exception {
    var service=service();service.setEnabled(true);
    when(generator.generate(any())).thenAnswer(inv->{service.tick();return "message";});service.tick();verify(generator,times(1)).generate(any());
  }
  @Test void recoveryDuringGenerationDiscardsStaleNag() throws Exception {
    var service=service();service.setEnabled(true);
    when(generator.generate(any())).thenAnswer(inv->{var work=record(0,3600,"development");when(store.findBetween(any(),any())).thenReturn(sessions(List.of(work)));when(store.findRecordsBetween(any(),any())).thenReturn(List.of(work));return "message";});
    service.tick();verifyNoInteractions(publisher);
    verify(state).appendEvent(argThat(e->e.outcome()==BehaviorTimelineEvent.Outcome.SUPPRESSED && e.reason().equals("CONTEXT_CHANGED")));
  }
  @Test void sqliteCheckpointSurvivesRestartAndAvoidsRepeatedWrites() throws Exception {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("behavior.db"));
    var persistence=new SqliteBehaviorStateStore(ds);var a=assess(3600,record(0,3600,"social"));
    var checkpoint=new BehaviorNotificationPolicy(properties).decide(a,BehaviorState.empty(),true,false).state().notified(a);
    persistence.save(checkpoint,a);var reopened=new SqliteBehaviorStateStore(ds);assertEquals(checkpoint,reopened.load());
    assertFalse(new BehaviorNotificationPolicy(properties).decide(a,reopened.load(),true,false).allowed());
    service();properties.setEnabled(true);var service=new BehaviorService(properties,activity,capture,store,reopened,generator,publisher,tracker,clock);
    service.tick();service.tick();verifyNoInteractions(generator,publisher);
    try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM activity_behavior_transitions")) {assertTrue(r.next());assertEquals(1,r.getInt(1));}
  }
  @Test void transitionsAreBounded() throws Exception {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("bounded.db"));var persistence=new SqliteBehaviorStateStore(ds);
    var a=assess(1800,record(0,1800,"social"));for(int i=0;i<105;i++) persistence.save(BehaviorState.empty(),a);
    try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM activity_behavior_transitions")) {assertTrue(r.next());assertEquals(100,r.getInt(1));}
  }
  @Test void notificationHistoryDistinguishesDeliveryFromCooldownAndManualAssessment() throws Exception {
    var service=service();service.evaluate();verify(state,never()).appendEvent(any());
    service.setEnabled(true);service.tick();service.tick();
    var events=org.mockito.ArgumentCaptor.forClass(BehaviorTimelineEvent.class);verify(state,times(2)).appendEvent(events.capture());
    assertEquals(BehaviorTimelineEvent.Outcome.EMITTED,events.getAllValues().get(0).outcome());
    assertEquals(clock.instant(),events.getAllValues().get(0).timestamp());
    assertEquals(BehaviorSeverity.WARNING,events.getAllValues().get(0).severity());
    assertEquals(BehaviorTimelineEvent.Outcome.SUPPRESSED,events.getValue().outcome());assertEquals("COOLDOWN",events.getValue().reason());
  }
  @Test void failedPublicationNeverRecordsEmittedAndHistoryFailureDoesNotChangeDelivery() throws Exception {
    var service=service();service.setEnabled(true);doThrow(new IllegalStateException()).when(publisher).publish(any());service.tick();
    verify(state,never()).appendEvent(argThat(e->e.outcome()==BehaviorTimelineEvent.Outcome.EMITTED));
    verify(state).appendEvent(argThat(e->e.outcome()==BehaviorTimelineEvent.Outcome.FAILED));
    reset(state,publisher);service=service();service.setEnabled(true);doThrow(new IllegalStateException()).when(state).appendEvent(any());
    service.tick();verify(publisher).publish(any());assertTrue(service.status().contains("DELIVERED"));
  }
  @Test void eventUsesPublicationTimeAfterGenerationRatherThanReservationTime() throws Exception {
    service();var now=new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
    var moving=new Clock() {
      public ZoneId getZone(){return ZoneOffset.UTC;}
      public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
      public Instant instant(){return now.get();}
    };
    when(generator.generate(any())).thenAnswer(inv->{now.set(clock.instant().plusSeconds(30));return "message";});
    var service=new BehaviorService(properties,activity,capture,store,state,generator,publisher,tracker,moving);service.setEnabled(true);service.tick();
    var event=org.mockito.ArgumentCaptor.forClass(BehaviorTimelineEvent.class);verify(state).appendEvent(event.capture());
    assertEquals(clock.instant().plusSeconds(30),event.getValue().timestamp());
    assertEquals(BehaviorTimelineEvent.Outcome.EMITTED,event.getValue().outcome());
    verify(publisher).publish(argThat(m->m.createdAt().equals(event.getValue().timestamp()) && m.id().equals(event.getValue().id())));
  }
  @Test void slashActionsUseRuntimeOverrideAndEvaluateNeverNotifies() throws Exception {
    var service=service();var command=new picocli.CommandLine(new ActivityCommand(null,capture,activity,service));
    var output=new java.io.StringWriter();command.setOut(new java.io.PrintWriter(output));
    assertEquals(0,command.execute("behavior","on"));assertTrue(service.enabled());
    assertEquals(0,command.execute("behavior","status"));assertTrue(output.toString().contains("enabled=true"));
    assertEquals(0,command.execute("behavior","evaluate"));verifyNoInteractions(generator,publisher);verify(state,never()).save(any(),any());
    assertTrue(output.toString().contains("60分窓: 観測成功="));assertTrue(output.toString().contains("120分窓: 観測成功="));
    assertTrue(output.toString().contains("(評価対象の"));
    assertEquals(0,command.execute("behavior","off"));assertFalse(service.enabled());assertEquals(2,command.execute("behavior","invalid"));
  }
}
