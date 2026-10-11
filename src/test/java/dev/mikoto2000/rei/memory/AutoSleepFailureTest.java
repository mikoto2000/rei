package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;

@Tag("integration")
class AutoSleepFailureTest {
  @TempDir Path dir;
  final MemoryProperties memory=new MemoryProperties(true,20,80,10,3,2000,60,null);
  final AutoSleepProperties config=new AutoSleepProperties(true,Duration.ofSeconds(1),1,Duration.ofSeconds(1));
  final ConversationTurnStore turns=ConversationTurnStore.inMemory();
  final AtomicInteger calls=new AtomicInteger();
  final List<Duration> waits=new java.util.concurrent.CopyOnWriteArrayList<>();
  final AtomicReference<RuntimeException> failure=new AtomicReference<>();
  final MutableClock clock=new MutableClock();
  MemoryRepository repository;
  SleepService sleep;
  AutoSleepService auto;
  MemoryEvents events=mock(MemoryEvents.class);
  @BeforeEach void setup(){
    repository=repository();
    MemoryCandidateExtractor extractor=new MemoryCandidateExtractor() {
      public List<dev.mikoto2000.rei.memory.model.MemoryCandidate> extract(List<ConversationTurnStore.Turn> batch) {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        calls.incrementAndGet();var error=failure.get();if(error!=null)throw error;return List.of();
      }
      public List<dev.mikoto2000.rei.memory.model.MemoryCandidate> extract(List<ConversationTurnStore.Turn> batch,dev.mikoto2000.rei.llm.ModelCallBudget budget) {
        waits.add(((AutoSleepExecution)budget).waitTime());return MemoryCandidateExtractor.super.extract(batch,budget);
      }
    };
    sleep=new SleepService(repository,turns,extractor,new MemoryResolver((c,e)->null,memory),memory);
    auto=create();
    add("s");
  }
  MemoryRepository repository(){var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"));return new MemoryRepository(ds,new MemoryService(ds,memory));}
  AutoSleepService create(){var result=new AutoSleepService(sleep,memory,config,new DefaultAgentActivityTracker(Clock.fixed(clock.instant().minusSeconds(600),ZoneOffset.UTC)),clock);result.setEvents(events);return result;}
  void add(String session){var owner=new AgentRunContext(UUID.randomUUID().toString(),session,dir,"p");turns.start(owner,"fact");turns.finish(owner,ConversationTurnStore.Status.COMPLETED,"agreed");auto.afterTerminal(owner);}
  void attempt() throws Exception {
    clock.advance();auto.tick();
    for(int i=0;i<200;i++){Thread.sleep(5);if(!auto.isExecuting("s")&&repository.autoState("s","p").revision()>revision)return;}
  }
  long revision;
  void runAttempt() throws Exception {revision=repository.autoState("s","p").revision();attempt();}
  @AfterEach void close(){auto.close();}
  @Test void threeTimeoutsStopPersistAndNotifyOnlyOnceAndResumeWithoutModel() throws Exception {
    failure.set(new AutoSleepExecution.Failure("MODEL_TIMEOUT",null));
    assertEquals(120,auto.timeout(0).toSeconds());runAttempt();assertEquals(240,auto.timeout(repository.autoState("s","p").stage()).toSeconds());
    runAttempt();assertEquals(480,auto.timeout(repository.autoState("s","p").stage()).toSeconds());runAttempt();
    assertEquals(List.of(Duration.ofSeconds(120),Duration.ofSeconds(240),Duration.ofSeconds(480)),waits);
    assertEquals(3,calls.get());assertNotNull(repository.autoState("s","p").stoppedAt());assertEquals(0,repository.lastProcessed("s"));
    for(int i=0;i<4;i++){clock.advance();auto.tick();}assertEquals(3,calls.get());
    verify(events,times(1)).publish(eq(dev.mikoto2000.rei.event.AgentEventType.AUTO_SLEEP_STOPPED),eq("p"),eq("s"),isNull(),eq(false),eq(0),eq(0),eq(0),contains("480"));
    add("s");sleep.requestAutoSleep("s","p","shutdown");auto.close();auto=create();auto.afterTerminal(new AgentRunContext("r","s",dir,"p"));clock.advance();auto.tick();
    assertEquals(3,calls.get());assertEquals(3,repository().autoState("s","p").failures());assertTrue(auto.status("s","p").contains("MODEL_TIMEOUT"));
    auto.resume("s","p");assertEquals(3,calls.get());assertEquals(0,repository.autoState("s","p").stage());
    failure.set(null);runAttempt();assertEquals(4,calls.get());assertEquals(2,repository.lastProcessed("s"));
  }
  @Test void nonTimeoutAndSuccessMaintainThenResetStage() throws Exception {
    failure.set(new AutoSleepExecution.Failure("MODEL_TIMEOUT",null));runAttempt();
    failure.set(new AutoSleepExecution.Failure("MODEL_REQUEST_FAILED",null));runAttempt();assertEquals(1,repository.autoState("s","p").stage());
    failure.set(null);runAttempt();var state=repository.autoState("s","p");assertEquals(0,state.failures());assertEquals(0,state.stage());assertEquals(1,repository.lastProcessed("s"));
  }
  @Test void cancellationBudgetAndZeroPendingDoNotResetFailures() throws Exception {
    failure.set(new AutoSleepExecution.Failure("MODEL_TIMEOUT",null));runAttempt();
    failure.set(new java.util.concurrent.CancellationException());clock.advance();auto.tick();auto.close();assertEquals(1,repository.autoState("s","p").failures());
    auto=create();auto.afterTerminal(new AgentRunContext("r","s",dir,"p"));
    failure.set(new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED));clock.advance();auto.tick();auto.close();assertEquals(1,repository.autoState("s","p").failures());
    failure.set(null);sleep.sleep("s","p",false);assertEquals(1,repository.autoState("s","p").failures());sleep.sleep("s","p",true);assertEquals(1,repository.autoState("s","p").stage());
    auto=create();auto.afterTerminal(new AgentRunContext("r","s",dir,"p"));clock.advance();auto.tick();assertEquals(1,repository.autoState("s","p").failures());
  }
  @Test void staleRevisionAndOtherProjectCannotOverwrite() {
    var old=repository.autoState("s","p");var changed=repository.updateAutoState(old,"MODEL_TIMEOUT","safe",clock.instant().toString(),3);
    assertThrows(IllegalStateException.class,()->repository.updateAutoState(old,null,null,null,3));
    assertThrows(IllegalArgumentException.class,()->repository.autoState("s","other"));assertEquals(changed,repository.autoState("s","p"));
  }
  @Test void stoppedSessionDoesNotBlockAnother() throws Exception {
    var state=repository.autoState("s","p");for(int i=0;i<3;i++)state=repository.updateAutoState(state,"UNKNOWN","safe",clock.instant().toString(),3);
    add("other");clock.advance();auto.tick();
    for(int i=0;i<200&&repository.lastProcessed("other")==0;i++)Thread.sleep(5);
    auto.close();assertEquals(1,calls.get());assertEquals(1,repository.lastProcessed("other"));
  }
  @Test void elapsedLimitClampsWaitAndHasSeparateCode() {
    var ticks=new AtomicLong();var execution=new AutoSleepExecution(Duration.ofSeconds(480),Duration.ofSeconds(600),ticks::get);
    assertEquals(Duration.ofSeconds(480),execution.waitTime());ticks.set(Duration.ofSeconds(590).toNanos());assertEquals(Duration.ofSeconds(10),execution.waitTime());assertTrue(execution.waitLimit().deadline());
    ticks.set(Duration.ofSeconds(600).toNanos());assertEquals("AUTO_SLEEP_DEADLINE_EXCEEDED",assertThrows(AutoSleepExecution.Failure.class,execution::remaining).code);
  }
  @Test void stateReadFailureNeverStartsModelAndSuccessWriteFailureDoesNotReapply() throws Exception {
    auto.close();var guarded=spy(sleep);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable")).when(guarded).autoState("s","p");
    auto=new AutoSleepService(guarded,memory,config,new DefaultAgentActivityTracker(Clock.fixed(clock.instant().minusSeconds(600),ZoneOffset.UTC)),clock);
    auto.afterTerminal(new AgentRunContext("r","s",dir,"p"));clock.advance();auto.tick();assertEquals(0,calls.get());auto.close();
    doCallRealMethod().when(guarded).autoState("s","p");
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable")).when(guarded).updateAutoState(any(),isNull(),isNull(),anyString(),anyInt());
    auto=new AutoSleepService(guarded,memory,config,new DefaultAgentActivityTracker(Clock.fixed(clock.instant().minusSeconds(600),ZoneOffset.UTC)),clock);
    auto.afterTerminal(new AgentRunContext("r","s",dir,"p"));clock.advance();auto.tick();
    for(int i=0;i<200&&(repository.lastProcessed("s")==0||auto.isExecuting("s"));i++)Thread.sleep(5);
    assertEquals(1,repository.lastProcessed("s"));add("s");clock.advance();auto.tick();assertEquals(1,calls.get());
    doCallRealMethod().when(guarded).updateAutoState(any(),isNull(),isNull(),anyString(),anyInt());
    for(int i=0;i<200&&repository.lastProcessed("s")<2;i++){clock.advance();auto.tick();Thread.sleep(5);}
    assertEquals(2,calls.get());assertEquals(2,repository.lastProcessed("s"));
  }
  @Test void newInputDuringExtendedExecutionLeavesCheckpointAndCountersUnchanged() throws Exception {
    auto.close();var activity=new DefaultAgentActivityTracker(Clock.fixed(clock.instant().minusSeconds(600),ZoneOffset.UTC));
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var controlled=new SleepService(repository,turns,batch->{entered.countDown();try{release.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return List.of();},new MemoryResolver((c,e)->null,memory),memory);
    var state=repository.autoState("s","p");repository.updateAutoState(state,"MODEL_TIMEOUT","safe",clock.instant().toString(),3);
    auto=new AutoSleepService(controlled,memory,config,activity,clock);auto.afterTerminal(new AgentRunContext("r","s",dir,"p"));clock.advance();auto.tick();
    assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->auto.resume("s","p"));
    activity.recordUserActivity(clock.instant());auto.tick();release.countDown();auto.close();
    assertEquals(0,repository.lastProcessed("s"));assertEquals(1,repository.autoState("s","p").failures());assertEquals(1,repository.autoState("s","p").stage());
  }
  @Test void overallWatchdogInterruptsNonStreamWorkWithoutAdvancingProgress() {
    var controlled=new SleepService(repository,turns,batch->{try{new java.util.concurrent.CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}return List.of();},new MemoryResolver((c,e)->null,memory),memory);
    var error=assertThrows(AutoSleepExecution.Failure.class,()->controlled.sleep("s","p",false,()->false,new AutoSleepExecution(Duration.ofSeconds(480),Duration.ofMillis(30))));
    assertEquals("AUTO_SLEEP_DEADLINE_EXCEEDED",error.code);assertFalse(Thread.currentThread().isInterrupted());assertEquals(0,repository.lastProcessed("s"));
    assertEquals("FAILED",repository.history("p",10).getFirst().status());
  }
  @Test void disabledResumeResetsStateButNeverRunsModel() {
    var state=repository.autoState("s","p");for(int i=0;i<3;i++)state=repository.updateAutoState(state,"UNKNOWN","safe",clock.instant().toString(),3);
    auto.close();auto=new AutoSleepService(sleep,memory,new AutoSleepProperties(false,null,0,null),new DefaultAgentActivityTracker(clock),clock);
    assertTrue(auto.resume("s","p").contains("無効"));assertEquals(0,repository.autoState("s","p").failures());clock.advance();auto.tick();assertEquals(0,calls.get());
  }
  @Test void persistenceFailureRollsBackCheckpointAndCountsWithoutAdvancingStage() throws Exception {
    auto.close();var guarded=spy(repository);
    doAnswer(a->{SleepRun run=a.getArgument(0);if(run.status().equals("COMPLETED"))throw new org.springframework.dao.DataAccessResourceFailureException("save failed");a.callRealMethod();return null;}).when(guarded).saveRun(any());
    sleep=new SleepService(guarded,turns,batch->List.of(),new MemoryResolver((c,e)->null,memory),memory);
    auto=create();auto.afterTerminal(new AgentRunContext("r","s",dir,"p"));runAttempt();
    var state=repository.autoState("s","p");assertEquals(1,state.failures());assertEquals(0,state.stage());assertEquals("PERSISTENCE_FAILED",state.code());assertEquals(0,repository.lastProcessed("s"));
  }
  @Test void overallFailureCountsWithoutIncreasingTimeoutStage() throws Exception {
    failure.set(new AutoSleepExecution.Failure("AUTO_SLEEP_DEADLINE_EXCEEDED",null));runAttempt();
    assertEquals(1,repository.autoState("s","p").failures());assertEquals(0,repository.autoState("s","p").stage());
  }
  @Test void outputValidationIsCountedAndUnclassifiedIllegalStateIsNotTimeout() throws Exception {
    failure.set(new dev.mikoto2000.rei.subagent.SubAgentValidationException(List.of()));runAttempt();
    assertEquals("OUTPUT_INVALID",repository.autoState("s","p").code());
    failure.set(new IllegalStateException("Timeout after 120 seconds"));runAttempt();
    assertEquals("UNKNOWN",repository.autoState("s","p").code());assertEquals(0,repository.autoState("s","p").stage());
  }
  static class MutableClock extends Clock {
    Instant now=Instant.parse("2026-10-12T00:00:00Z");
    void advance(){now=now.plusSeconds(10);}
    public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
  }
}
