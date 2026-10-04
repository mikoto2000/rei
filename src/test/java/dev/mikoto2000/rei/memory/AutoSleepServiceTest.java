package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.*;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

class AutoSleepServiceTest {
  final Instant now = Instant.parse("2026-10-04T00:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final SleepService sleep = mock(SleepService.class);
  final DefaultAgentActivityTracker activity = new DefaultAgentActivityTracker(Clock.fixed(now.minusSeconds(600), ZoneOffset.UTC));
  final AgentRunContext owner = new AgentRunContext("r", "s", java.nio.file.Path.of("."), "p");
  AutoSleepService service;
  @AfterEach void close() { if (service != null) service.close(); }
  void setup(boolean enabled) {
    service = new AutoSleepService(sleep, new MemoryProperties(true,20,80,10,3,2000,60,null),
        new AutoSleepProperties(enabled, Duration.ofMinutes(5), 2, Duration.ofMinutes(10)), activity, clock);
    when(sleep.unsleptTurns("s")).thenReturn(2L);
    service.afterTerminal(owner);
  }
  @Test void disabledDoesNotReadOrRun() {
    setup(false); service.tick(); verifyNoInteractions(sleep);
  }
  @Test void invalidLimitsAreRejectedAndDefaultsAreOptIn() {
    assertFalse(new AutoSleepProperties(false,null,0,null).enabled());
    assertThrows(IllegalArgumentException.class,()->new AutoSleepProperties(true,Duration.ZERO,1,null));
    assertThrows(IllegalArgumentException.class,()->new AutoSleepProperties(true,null,-1,null));
  }
  @Test void idleSessionRunsOneBatchAndDoesNotImmediatelyRepeat() throws Exception {
    setup(true);
    CountDownLatch done = new CountDownLatch(1);
    doAnswer(a -> { done.countDown(); return null; }).when(sleep).sleep(eq("s"),eq("p"),eq(false),any());
    service.tick(); assertTrue(done.await(2,TimeUnit.SECONDS));
    service.tick(); verify(sleep,times(1)).sleep(eq("s"),eq("p"),eq(false),any());
  }
  @Test void busyOrRecentInputNeverStarts() {
    setup(true); activity.recordAgentStarted(now); service.tick();
    activity.recordAgentCompleted(now); service.tick();
    verify(sleep,never()).sleep(anyString(),anyString(),anyBoolean(),any());
  }
  @Test void insufficientTurnsNeverStarts() {
    setup(true); when(sleep.unsleptTurns("s")).thenReturn(1L); service.tick();
    verify(sleep,never()).sleep(anyString(),anyString(),anyBoolean(),any());
  }
  @Test void newActivityInvalidatesCancellationGuard() throws Exception {
    setup(true); CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
    var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(a -> {
      java.util.function.BooleanSupplier guard=a.getArgument(3);
      entered.countDown(); release.await(2,TimeUnit.SECONDS); cancelled.set(guard.getAsBoolean()); return null;
    }).when(sleep).sleep(eq("s"),eq("p"),eq(false),any());
    service.tick(); assertTrue(entered.await(2,TimeUnit.SECONDS));
    activity.recordUserActivity(now); release.countDown();
    service.close(); assertTrue(cancelled.get());
  }
  @Test void failureIsContainedAndRateLimited() throws Exception {
    setup(true); CountDownLatch entered=new CountDownLatch(1);
    doAnswer(a -> {entered.countDown(); throw new IllegalStateException("model unavailable");})
        .when(sleep).sleep(eq("s"),eq("p"),eq(false),any());
    assertDoesNotThrow(service::tick); assertTrue(entered.await(2,TimeUnit.SECONDS));
    service.tick(); verify(sleep,times(1)).sleep(eq("s"),eq("p"),eq(false),any());
  }
  @Test void cancellationAlsoProtectsManualSleepPersistence() {
    var turns=dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory();
    var repository=mock(MemoryRepository.class);
    var extractor=mock(MemoryCandidateExtractor.class);
    var props=new MemoryProperties(true,20,80,10,3,2000,60,null);
    var real=new SleepService(repository,turns,extractor,mock(MemoryResolver.class),props);
    assertThrows(CancellationException.class,()->real.sleep("s","p",false,()->true));
    verifyNoInteractions(extractor); verify(repository,never()).transaction(any());
  }
}
